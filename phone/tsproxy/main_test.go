package main

import (
	"bytes"
	"compress/gzip"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestCacheableRequest(t *testing.T) {
	cases := map[string]bool{
		"/m.js":                 true,
		"/m.css":                true,
		"/ref-digest.js":        true,
		"/icon-192.png":         true,
		"/m/index.html":         false,
		"/sw.js":                false,
		"/api/bootstrap":        false,
		"/manifest.webmanifest": false,
	}
	for p, want := range cases {
		if got := cacheableRequest(http.MethodGet, p); got != want {
			t.Errorf("cacheableRequest(%q)=%v want %v", p, got, want)
		}
	}
	if cacheableRequest(http.MethodPost, "/m.js") {
		t.Error("POST must not be cacheable")
	}
}

func TestServeCachedGzip(t *testing.T) {
	body := bytes.Repeat([]byte("console.log('hello');\n"), 500)
	e := &cacheEntry{
		status:  http.StatusOK,
		header:  http.Header{"Content-Type": {"text/javascript"}},
		body:    body,
		expires: time.Now().Add(time.Minute),
	}

	req := httptest.NewRequest(http.MethodGet, "/m.js", nil)
	req.Header.Set("Accept-Encoding", "gzip, deflate")
	rec := httptest.NewRecorder()
	serveCached(rec, req, e)
	resp := rec.Result()
	defer resp.Body.Close()
	if resp.Header.Get("Content-Encoding") != "gzip" {
		t.Fatalf("expected gzip, got %q", resp.Header.Get("Content-Encoding"))
	}
	zr, err := gzip.NewReader(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	got, err := io.ReadAll(zr)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, body) {
		t.Fatal("gzip body mismatch")
	}
	if resp.Header.Get("Cache-Control") != "public, max-age=600" {
		t.Fatalf("cache-control=%q", resp.Header.Get("Cache-Control"))
	}
}

func TestServeCachedIdentity(t *testing.T) {
	body := []byte("body")
	e := &cacheEntry{
		status:  http.StatusOK,
		header:  http.Header{"Content-Type": {"text/plain"}},
		body:    body,
		expires: time.Now().Add(time.Minute),
	}
	req := httptest.NewRequest(http.MethodGet, "/m.js", nil)
	req.Header.Set("Accept-Encoding", "identity")
	rec := httptest.NewRecorder()
	serveCached(rec, req, e)
	if rec.Body.Len() != len(body) || rec.Body.String() != "body" {
		t.Fatalf("unexpected body %q", rec.Body.String())
	}
}

func resetCache() {
	cacheMu.Lock()
	cache = make(map[string]*cacheEntry)
	cacheTotal = 0
	cacheMu.Unlock()
}

func makeResponse(method, path, ct, cc string, body []byte, gzipOK bool) *http.Response {
	req := httptest.NewRequest(method, path, nil)
	if gzipOK {
		req.Header.Set("Accept-Encoding", "gzip, deflate")
	}
	h := http.Header{"Content-Type": {ct}}
	if cc != "" {
		h.Set("Cache-Control", cc)
	}
	return &http.Response{
		StatusCode:    http.StatusOK,
		Header:        h,
		Body:          io.NopCloser(bytes.NewReader(body)),
		ContentLength: int64(len(body)),
		Request:       req,
	}
}

func TestModifyResponseStaticGzipAndCache(t *testing.T) {
	resetCache()
	body := bytes.Repeat([]byte("console.log('hello');\n"), 500)
	resp := makeResponse(http.MethodGet, "/m.js", "text/javascript", "no-cache", body, true)
	if err := modifyResponse(resp); err != nil {
		t.Fatal(err)
	}
	if resp.Header.Get("Content-Encoding") != "gzip" {
		t.Fatalf("encoding=%q", resp.Header.Get("Content-Encoding"))
	}
	if resp.Header.Get("Cache-Control") != "public, max-age=600" {
		t.Fatalf("cache-control=%q", resp.Header.Get("Cache-Control"))
	}
	if cacheGet("/m.js") == nil {
		t.Fatal("static asset not cached")
	}
	zr, err := gzip.NewReader(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	got, err := io.ReadAll(zr)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, body) {
		t.Fatal("gzip body mismatch")
	}
}

func TestModifyResponsePageGzipNoCache(t *testing.T) {
	resetCache()
	body := bytes.Repeat([]byte("<div>page</div>\n"), 200)
	resp := makeResponse(http.MethodGet, "/m/index.html", "text/html; charset=utf-8", "", body, true)
	if err := modifyResponse(resp); err != nil {
		t.Fatal(err)
	}
	if resp.Header.Get("Content-Encoding") != "gzip" {
		t.Fatalf("encoding=%q", resp.Header.Get("Content-Encoding"))
	}
	if cacheGet("/m/index.html") != nil {
		t.Fatal("page must not be cached")
	}
}

func TestModifyResponseSSENotTouched(t *testing.T) {
	resetCache()
	body := []byte("data: hello\n\n")
	resp := makeResponse(http.MethodGet, "/api/stream", "text/event-stream; charset=utf-8",
		"no-cache, no-transform", body, true)
	if err := modifyResponse(resp); err != nil {
		t.Fatal(err)
	}
	if resp.Header.Get("Content-Encoding") != "" {
		t.Fatalf("SSE must not be compressed, got %q", resp.Header.Get("Content-Encoding"))
	}
	got, _ := io.ReadAll(resp.Body)
	if string(got) != string(body) {
		t.Fatalf("SSE body changed: %q", got)
	}
}

func TestModifyResponseIdentityStillCaches(t *testing.T) {
	resetCache()
	body := []byte("body")
	resp := makeResponse(http.MethodGet, "/m.css", "text/css", "no-cache", body, false)
	if err := modifyResponse(resp); err != nil {
		t.Fatal(err)
	}
	if resp.Header.Get("Content-Encoding") != "" {
		t.Fatal("identity request must not get gzip")
	}
	if cacheGet("/m.css") == nil {
		t.Fatal("static asset should still be cached")
	}
}

func TestCachePutGet(t *testing.T) {
	cacheMu.Lock()
	cache = make(map[string]*cacheEntry)
	cacheTotal = 0
	cacheMu.Unlock()

	h := http.Header{"Content-Type": {"text/css"}, "Set-Cookie": {"secret=1"}}
	cachePut("/m.css", h, []byte("body"))
	e := cacheGet("/m.css")
	if e == nil || string(e.body) != "body" {
		t.Fatal("cache miss")
	}
	if e.header.Get("Set-Cookie") != "" {
		t.Fatal("Set-Cookie must not be cached")
	}
}
