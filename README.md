# Noticias infoLibre
Independent Android RSS reader for infoLibre.
- RSS: https://www.infolibre.es/rss/
- Search, refresh, sharing and article view
- Offline JSON/image cache
- Full connectivity diagnostics and passive NetworkObserver
- Latest Java ConnectivityAndInternetAccess gist implementation integrated in the app source
- Android API 16+ / Java 8
This is not an official infoLibre application.

Connectivity implementation source: https://gist.github.com/rodrigosambadesaa/729cca29a031fef4e2f15751863b655f

Current release: 1.1.13

Connectivity source revision: `3b0497e976765653a7467e3bd7d6bff28b96bd7c`.

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
The header title uses high-contrast white text with a medium weight and subtle
shadow for clearer separation from the blue background.
The RSS downloader also checks connectivity before creating its progress dialog,
so offline startup does not show a false download state.
The network header now uses the observer's single coherent state, and offline
startup avoids duplicate Toast notifications.
Network availability is now separated from validated Internet access for the
header, diagnostics and remote-request guard.
VPN networks such as AdGuard are shown separately, but a VPN-only/dangling VPN
without usable Wi-Fi, cellular or Ethernet is treated as offline by the cheap
`isConnected()` + `hasPhysicalNetwork()` guard. With a physical network present,
the active RSS/Internet probe remains authoritative for the final result.
