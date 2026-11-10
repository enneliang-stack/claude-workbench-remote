// 内置 Tailscale 用户态反向代理。
//
// 不创建 Android VPN，不占用系统 VPN 通道；通过 tsnet 加入 tailnet，
// 在本机 127.0.0.1 上提供 HTTP 反向代理，把请求转给电脑的 Tailscale 地址。
package main

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"tailscale.com/tsnet"
)

const (
	defaultListen = "127.0.0.1:4720"

	// 页面静态资源在手机本地缓存 10 分钟：外层 Tailscale 中继延迟高，
	// 让 m.js / m.css / 图标这些不常变的东西不再每次都走一遍中继。
	cacheTTL       = 10 * time.Minute
	cacheMaxBytes  = 8 << 20
	cacheMaxEntry  = 2 << 20
	clientCacheAge = 600

	// 压缩缓冲上限。超过这个大小的响应直接跳过压缩（比如大的会话 JSON），
	// 避免在手机上占太多内存；聊天 SSE 走 no-transform 分支，不会被缓冲。
	gzipBufferMax = 4 << 20
)

type proxyState struct {
	mu      sync.RWMutex
	up      bool
	lastErr string
	target  string
}

func (s *proxyState) snapshot() (bool, string) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.up, s.lastErr
}

func (s *proxyState) setUp() {
	s.mu.Lock()
	s.up = true
	s.lastErr = ""
	s.mu.Unlock()
}

func (s *proxyState) setErr(err error) {
	s.mu.Lock()
	s.up = false
	if err != nil {
		s.lastErr = err.Error()
	}
	s.mu.Unlock()
}

// ---------------------------------------------------------------- 静态缓存

type cacheEntry struct {
	status  int
	header  http.Header
	body    []byte
	expires time.Time
}

var (
	cacheMu    sync.Mutex
	cache      = make(map[string]*cacheEntry)
	cacheTotal int
)

// 只缓存明确的静态资源。页面本身带 token、manifest 会注入 token、
// Service Worker 需要及时更新，都不缓存。
func cacheableRequest(method, p string) bool {
	if method != http.MethodGet {
		return false
	}
	base := strings.ToLower(path.Base(p))
	if base == "sw.js" || base == "service-worker.js" {
		return false
	}
	switch base {
	case "m.css", "m.js", "ref-digest.js":
		return true
	}
	switch strings.ToLower(path.Ext(p)) {
	case ".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".webp", ".woff", ".woff2", ".ttf":
		return true
	}
	return false
}

func cacheGet(key string) *cacheEntry {
	cacheMu.Lock()
	defer cacheMu.Unlock()
	e := cache[key]
	if e == nil {
		return nil
	}
	if time.Now().After(e.expires) {
		delete(cache, key)
		cacheTotal -= len(e.body)
		return nil
	}
	return e
}

func cachePut(key string, h http.Header, body []byte) {
	if len(body) == 0 || len(body) > cacheMaxEntry {
		return
	}
	cacheMu.Lock()
	defer cacheMu.Unlock()

	if old := cache[key]; old != nil {
		cacheTotal -= len(old.body)
	}
	for cacheTotal+len(body) > cacheMaxBytes && len(cache) > 0 {
		for k, e := range cache {
			delete(cache, k)
			cacheTotal -= len(e.body)
			break
		}
	}

	ch := h.Clone()
	ch.Del("content-encoding")
	ch.Del("content-length")
	ch.Del("set-cookie")
	cache[key] = &cacheEntry{
		status:  http.StatusOK,
		header:  ch,
		body:    body,
		expires: time.Now().Add(cacheTTL),
	}
	cacheTotal += len(body)
}

func cacheStats() (int, int) {
	cacheMu.Lock()
	defer cacheMu.Unlock()
	return len(cache), cacheTotal
}

func copyHeader(dst, src http.Header) {
	for k, vv := range src {
		for _, v := range vv {
			dst.Add(k, v)
		}
	}
}

func acceptsGzip(r *http.Request) bool {
	if r == nil {
		return false
	}
	for _, part := range strings.Split(r.Header.Get("Accept-Encoding"), ",") {
		part = strings.TrimSpace(part)
		if part == "gzip" || strings.HasPrefix(part, "gzip;") {
			if strings.Contains(part, "q=0") {
				continue
			}
			return true
		}
	}
	return false
}

func compressibleType(ct string) bool {
	ct = strings.ToLower(strings.TrimSpace(strings.SplitN(ct, ";", 2)[0]))
	switch ct {
	case "text/html", "text/css", "text/plain", "text/javascript",
		"application/javascript", "application/json", "application/xml",
		"text/xml", "image/svg+xml":
		return true
	}
	return false
}

func gzipBytes(body []byte) ([]byte, bool) {
	var buf bytes.Buffer
	zw, err := gzip.NewWriterLevel(&buf, gzip.BestSpeed)
	if err != nil {
		return nil, false
	}
	if _, err := zw.Write(body); err != nil {
		return nil, false
	}
	if err := zw.Close(); err != nil {
		return nil, false
	}
	if buf.Len() >= len(body) {
		return nil, false
	}
	return buf.Bytes(), true
}

func serveCached(w http.ResponseWriter, r *http.Request, e *cacheEntry) {
	h := e.header.Clone()
	h.Set("Cache-Control", "public, max-age="+strconv.Itoa(clientCacheAge))

	if acceptsGzip(r) && compressibleType(h.Get("Content-Type")) {
		if gz, ok := gzipBytes(e.body); ok {
			h.Set("Content-Encoding", "gzip")
			h.Add("Vary", "Accept-Encoding")
			h.Set("Content-Length", strconv.Itoa(len(gz)))
			copyHeader(w.Header(), h)
			w.WriteHeader(e.status)
			_, _ = w.Write(gz)
			return
		}
	}

	h.Set("Content-Length", strconv.Itoa(len(e.body)))
	copyHeader(w.Header(), h)
	w.WriteHeader(e.status)
	_, _ = w.Write(e.body)
}

// ---------------------------------------------------------------- 状态

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// 给 App 显示当前是直连还是中继。tailscale ping 里表现为：
// 直连：CurAddr 非空；中继：CurAddr 为空、Relay 是区域名（sfo/lax/...）。
func peerPath(ctx context.Context, srv *tsnet.Server, target *url.URL) (found, direct bool, relay string, online bool) {
	lc, err := srv.LocalClient()
	if err != nil || lc == nil {
		return
	}
	ctx, cancel := context.WithTimeout(ctx, 600*time.Millisecond)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil || st == nil {
		return
	}
	host := strings.TrimSuffix(target.Hostname(), ".")
	for _, p := range st.Peer {
		match := false
		for _, ip := range p.TailscaleIPs {
			if ip.String() == host {
				match = true
				break
			}
		}
		if !match && strings.EqualFold(strings.TrimSuffix(p.DNSName, "."), host) {
			match = true
		}
		if !match && strings.EqualFold(p.HostName, host) {
			match = true
		}
		if match {
			return true, p.CurAddr != "", p.Relay, p.Online
		}
	}
	return
}

// 静态资源缓存 + gzip。单独拆出来便于测试；响应头/正文会就地修改。
func modifyResponse(resp *http.Response) error {
	if resp == nil || resp.Request == nil {
		return nil
	}
	req := resp.Request
	ct := resp.Header.Get("Content-Type")
	cacheable := req.Method == http.MethodGet && resp.StatusCode == http.StatusOK &&
		cacheableRequest(req.Method, req.URL.Path) && resp.Header.Get("Content-Encoding") == ""

	// 静态资源：先读进内存，给代理缓存一份，同时方便下面直接压缩。
	var buffered []byte
	if cacheable {
		limited := io.LimitReader(resp.Body, cacheMaxEntry+1)
		body, err := io.ReadAll(limited)
		if err != nil {
			// 读失败时把已经读出的部分接回去，保持响应完整。
			resp.Body = io.NopCloser(io.MultiReader(bytes.NewReader(body), resp.Body))
		} else if len(body) > cacheMaxEntry {
			resp.Body = io.NopCloser(io.MultiReader(bytes.NewReader(body), resp.Body))
		} else {
			_ = resp.Body.Close()
			buffered = body
			resp.Body = io.NopCloser(bytes.NewReader(body))
			resp.ContentLength = int64(len(body))
			resp.Header.Set("Content-Length", strconv.Itoa(len(body)))
			cachePut(req.URL.Path, resp.Header, body)
			resp.Header.Set("Cache-Control", "public, max-age="+strconv.Itoa(clientCacheAge))
		}
	}

	// Gzip。SSE 明确带 no-transform，不能缓冲，否则聊天流会一整段卡住。
	skipGzip := strings.HasPrefix(strings.ToLower(ct), "text/event-stream") ||
		strings.Contains(strings.ToLower(resp.Header.Get("Cache-Control")), "no-transform") ||
		resp.Header.Get("Content-Encoding") != "" ||
		resp.StatusCode != http.StatusOK || req.Method == http.MethodHead ||
		!acceptsGzip(req) || !compressibleType(ct)

	if !skipGzip {
		if buffered == nil && (resp.ContentLength < 0 || resp.ContentLength <= gzipBufferMax) {
			limited := io.LimitReader(resp.Body, gzipBufferMax+1)
			body, err := io.ReadAll(limited)
			if err != nil {
				resp.Body = io.NopCloser(io.MultiReader(bytes.NewReader(body), resp.Body))
			} else if len(body) <= gzipBufferMax {
				_ = resp.Body.Close()
				buffered = body
				resp.Body = io.NopCloser(bytes.NewReader(body))
				resp.ContentLength = int64(len(body))
				resp.Header.Set("Content-Length", strconv.Itoa(len(body)))
			} else {
				resp.Body = io.NopCloser(io.MultiReader(bytes.NewReader(body), resp.Body))
			}
		}
		if buffered != nil && len(buffered) > 512 {
			if gz, ok := gzipBytes(buffered); ok {
				resp.Body = io.NopCloser(bytes.NewReader(gz))
				resp.ContentLength = int64(len(gz))
				resp.Header.Set("Content-Encoding", "gzip")
				resp.Header.Add("Vary", "Accept-Encoding")
				resp.Header.Del("Content-Length")
				resp.Header.Set("Content-Length", strconv.Itoa(len(gz)))
			}
		}
	}
	return nil
}

func main() {
	listen := env("TS_LISTEN", defaultListen)
	targetRaw := os.Getenv("TS_TARGET")
	authKey := os.Getenv("TS_AUTHKEY")
	stateDir := env("TS_STATE_DIR", "/opt/workbench/data/tsnet")
	hostname := env("TS_HOSTNAME", "claude-phone")
	preferDERP := env("TS_PREFER_DERP", "")

	if targetRaw == "" {
		log.Fatal("TS_TARGET is required")
	}
	target, err := url.Parse(targetRaw)
	if err != nil || target.Scheme == "" || target.Host == "" {
		log.Fatalf("invalid TS_TARGET: %q", targetRaw)
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		log.Fatalf("create state dir: %v", err)
	}

	state := &proxyState{target: targetRaw}
	srv := &tsnet.Server{
		Hostname: hostname,
		AuthKey:  authKey,
		Dir:      stateDir,
		Logf: func(format string, args ...any) {
			log.Printf("[tsnet] "+format, args...)
		},
	}
	defer srv.Close()

	// 先把本地 HTTP 服务立起来，让 App 可以查状态；tsnet 在后台登录。
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 180*time.Second)
		defer cancel()
		if _, err := srv.Up(ctx); err != nil {
			state.setErr(err)
			log.Printf("[tsnet] up failed: %v", err)
			return
		}
		state.setUp()
		log.Printf("[tsnet] up as %s", hostname)
		// 手机到 hkg 很近，但电脑到 hkg 可能比对 sfo 慢很多；
		// 中继路径是两端之和，可用 TS_PREFER_DERP 指定两边都近的 DERP 区域。
		if preferDERP != "" {
			ctx2, cancel2 := context.WithTimeout(context.Background(), 8*time.Second)
			if lc, err := srv.LocalClient(); err == nil {
				if err := lc.DebugActionBody(ctx2, "force-prefer-derp", strings.NewReader(preferDERP)); err != nil {
					log.Printf("[tsnet] prefer DERP %s failed: %v", preferDERP, err)
				} else {
					log.Printf("[tsnet] preferred DERP region %s", preferDERP)
				}
			}
			cancel2()
		}
	}()

	proxy := httputil.NewSingleHostReverseProxy(target)
	proxy.FlushInterval = -1
	proxy.Transport = &http.Transport{
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			up, _ := state.snapshot()
			if !up {
				return nil, errors.New("内置 Tailscale 尚未连接")
			}
			return srv.Dial(ctx, network, address)
		},
		MaxIdleConns:          64,
		MaxIdleConnsPerHost:   32,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   20 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
		// 压缩由本代理统一处理，不让 Transport 自己加 Accept-Encoding 再解压。
		DisableCompression: true,
		ForceAttemptHTTP2:  true,
	}
	proxy.ModifyResponse = modifyResponse
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		up, lastErr := state.snapshot()
		msg := "内置 Tailscale 未连接"
		if up {
			msg = "内置 Tailscale 代理失败"
		}
		if lastErr != "" {
			msg += "：" + lastErr
		} else if err != nil {
			msg += "：" + err.Error()
		}
		http.Error(w, msg, http.StatusBadGateway)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/__tsproxy/status", func(w http.ResponseWriter, r *http.Request) {
		up, lastErr := state.snapshot()
		found, direct, relay, peerOnline := false, false, "", false
		if up {
			found, direct, relay, peerOnline = peerPath(r.Context(), srv, target)
		}
		entries, bytesCached := cacheStats()
		w.Header().Set("content-type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"ok":         true,
			"up":         up,
			"error":      lastErr,
			"target":     targetRaw,
			"listen":     listen,
			"version":    "1.1.0",
			"peerFound":  found,
			"direct":     direct,
			"relay":      relay,
			"peerOnline": peerOnline,
			"cacheItems": entries,
			"cacheBytes": bytesCached,
		})
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if cacheableRequest(r.Method, r.URL.Path) {
			if e := cacheGet(r.URL.Path); e != nil {
				serveCached(w, r, e)
				return
			}
		}
		proxy.ServeHTTP(w, r)
	})

	lc := net.ListenConfig{
		Control: func(network, address string, c syscall.RawConn) error {
			var opErr error
			if err := c.Control(func(fd uintptr) {
				opErr = setReuseAddr(fd)
			}); err != nil {
				return err
			}
			return opErr
		},
	}
	ln, err := lc.Listen(context.Background(), "tcp", listen)
	if err != nil {
		log.Fatalf("listen %s: %v", listen, err)
	}
	defer ln.Close()

	log.Printf("[proxy] listening %s -> %s", listen, targetRaw)
	server := &http.Server{
		Handler:           mux,
		ReadHeaderTimeout: 20 * time.Second,
	}
	if err := server.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
