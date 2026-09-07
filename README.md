# Noticias infoLibre
Independent Android RSS reader for infoLibre.
- RSS: https://www.infolibre.es/rss/
- Search, refresh, sharing and article view
- Offline JSON/image cache
- Full connectivity diagnostics and passive NetworkObserver
- Complete ConnectivityAndInternetAccess gist vendored in third_party/connectivity
- Android API 16+ / Java 8
This is not an official infoLibre application.

Connectivity implementation source: https://gist.github.com/rodrigosambadesaa/729cca29a031fef4e2f15751863b655f

Current release: 1.1.5

Network policy: use Android's passive `NetworkCapabilities` state as a cheap guard, let
the real RSS/article request be authoritative, and run the Gist's active diagnostic only
after an ambiguous connectivity failure. Offline, refresh, retry and pagination paths
reuse the news cache without redundant probes.
Changing screen orientation preserves the loaded list and current scroll position
without downloading the RSS again.
The top bars account for status-bar insets and keep the title separated from the
network status indicator on narrow screens.
The blue top bar uses a taller 72dp base height so the upper elements have
adequate visual spacing.
