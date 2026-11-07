'use strict';

// 移动端 PWA 的最小 Service Worker：不做缓存，只满足 HTTPS 下的可安装条件。
// 局域网 HTTP 打开时不会注册（浏览器也不允许），仍可通过「添加到主屏幕」使用。

self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', event => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', event => {
  event.respondWith(fetch(event.request));
});
