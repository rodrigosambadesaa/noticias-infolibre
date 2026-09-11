package com.example.muyinteresante;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.view.OnApplyWindowInsetsListener;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.WindowInsetsCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.example.muyinteresante.util.ConnectivityAndInternetAccess;
import com.example.muyinteresante.util.NewsCacheManager;
import com.example.muyinteresante.util.RemoteOperationPolicy;
import com.example.muyinteresanteNoTocar.DescargaNoticiasRSS;
import com.example.muyinteresanteNoTocar.NoticiaRSS;
import com.example.muyinteresanteNoTocar.iNoticiaRSS;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity implements iNoticiaRSS {

    private static final String TAG = "MainActivity";
    private static final String RSS_URL = "https://www.infolibre.es/rss/";
    private static final int NEWS_PAGE_SIZE = 10;
    private static final int LOAD_MORE_THRESHOLD = 4;
    private static final int MAX_CONSECUTIVE_DUPLICATE_PAGES = 2;

    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView rvNoticias;
    private NoticiasAdapter adapter;

    private LinearLayout bannerNetworkNotice;
    private TextView tvBannerText;
    private Button btnDiagnosticarRed;

    private LinearLayout layoutNetworkStatusPill;
    private View viewNetworkDot;
    private TextView tvNetworkStatusText;

    private LinearLayout layoutEmptyState;
    private Button btnReintentar;

    private ConnectivityAndInternetAccess.NetworkObserver networkObserver;
    private ConnectivityAndInternetAccess.NetworkState currentNetworkState;
    private boolean hasObservedNetworkState;
    private String lastConnectivityToast;

    private boolean isLoadingMore = false;
    private boolean hasMoreNews = false;
    private boolean remoteOperationInFlight = false;
    private DescargaNoticiasRSS.FailureType lastRssFailure = DescargaNoticiasRSS.FailureType.NONE;
    private int nextArchivePage = 2;
    private int consecutiveDuplicatePages = 0;
    private final ArrayList<NoticiaRSS> newsPool = new ArrayList<>();
    private int nextNewsIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        rvNoticias = findViewById(R.id.rvNoticias);
        bannerNetworkNotice = findViewById(R.id.bannerNetworkNotice);
        tvBannerText = findViewById(R.id.tvBannerText);
        btnDiagnosticarRed = findViewById(R.id.btnDiagnosticarRed);

        layoutNetworkStatusPill = findViewById(R.id.layoutNetworkStatusPill);
        viewNetworkDot = findViewById(R.id.viewNetworkDot);
        tvNetworkStatusText = findViewById(R.id.tvNetworkStatusText);

        layoutEmptyState = findViewById(R.id.layoutEmptyState);
        btnReintentar = findViewById(R.id.btnReintentar);

        // Soporte para márgenes de ventana/cámara en smartphones tipo S25 Ultra
        final View rootView = findViewById(android.R.id.content);
        if (rootView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootView, new OnApplyWindowInsetsListener() {
                @Override
                public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                    int top = insets.getSystemWindowInsetTop();
                    int bottom = insets.getSystemWindowInsetBottom();
                    int left = insets.getSystemWindowInsetLeft();
                    int right = insets.getSystemWindowInsetRight();

                    if (toolbar != null && top > 0) {
                        int baseToolbarHeight = getResources().getDimensionPixelSize(R.dimen.top_bar_height);
                        ViewGroup.LayoutParams toolbarParams = toolbar.getLayoutParams();
                        toolbarParams.height = baseToolbarHeight + top;
                        toolbar.setLayoutParams(toolbarParams);
                        toolbar.setPadding(left, top, right, 0);
                    }
                    if (rvNoticias != null && bottom > 0) {
                        rvNoticias.setPadding(left, rvNoticias.getPaddingTop(), right, bottom + 12);
                    }
                    return insets;
                }
            });
        }

        final LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        rvNoticias.setLayoutManager(layoutManager);
        adapter = new NoticiasAdapter(this, new ArrayList<NoticiaRSS>(), new NoticiasAdapter.OnNoticiaClickListener() {
            @Override
            public void onNoticiaClick(NoticiaRSS noticia) {
                if (noticia != null && noticia.getEnlace() != null) {
                    Intent intent = new Intent(MainActivity.this, DetalleActivity.class);
                    intent.putExtra(DetalleActivity.EXTRA_URL, noticia.getEnlace());
                    intent.putExtra(DetalleActivity.EXTRA_TITULO, noticia.getTitulo());
                    startActivity(intent);
                }
            }
        });
        rvNoticias.setAdapter(adapter);

        // Infinite scroll: cuando el usuario se aproxima al final se solicita la
        // siguiente página del feed oficial, que contiene noticias más antiguas.
        rvNoticias.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy <= 0 || isLoadingMore || !hasMoreNews || adapter == null) {
                    return;
                }

                int totalItems = adapter.getItemCount();
                int lastVisibleItem = layoutManager.findLastVisibleItemPosition();
                if (totalItems > 0 && lastVisibleItem >= totalItems - 1 - LOAD_MORE_THRESHOLD) {
                    cargarMasNoticias();
                }
            }
        });

        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                ejecutarDescargarNoticias();
            }
        });

        btnReintentar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ejecutarDescargarNoticias();
            }
        });

        View.OnClickListener listenerDiagnostico = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ejecutarDiagnosticoRedCompleto();
            }
        };
        layoutNetworkStatusPill.setOnClickListener(listenerDiagnostico);
        btnDiagnosticarRed.setOnClickListener(listenerDiagnostico);

        // Cargar noticias iniciales (intenta descargar o usa caché offline)
        cargarNoticiasIniciales();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // La actividad no se recrea al rotar: se conserva la lista y el scroll.
        Log.d(TAG, "Cambio de orientación: se conserva la lista y la posición de scroll");
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Iniciar el observador pasivo de conectividad basado en el Gist
        networkObserver = ConnectivityAndInternetAccess.observeNetwork(this, new ConnectivityAndInternetAccess.NetworkStateCallback() {
            @Override
            public void onStateChanged(ConnectivityAndInternetAccess.NetworkState state) {
                boolean wasConnected = currentNetworkState != null && currentNetworkState.isConnected();
                boolean wasCaptive = currentNetworkState != null && currentNetworkState.isCaptivePortalDetected();
                currentNetworkState = state;
                if (hasObservedNetworkState) {
                    if (state != null && state.isCaptivePortalDetected() && !wasCaptive) {
                        mostrarToastConectividad(getString(R.string.network_captive_portal));
                    } else if (state != null && !state.isConnected() && wasConnected) {
                        mostrarToastConectividad(getString(R.string.network_no_connection));
                    } else if (state != null && state.isConnected() && !wasConnected) {
                        mostrarToastConectividad(getString(R.string.network_recovered));
                    }
                }
                hasObservedNetworkState = true;
                actualizarInterfazEstadoRed(state);
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (networkObserver != null) {
            networkObserver.close();
            networkObserver = null;
        }
    }

    private void actualizarInterfazEstadoRed(ConnectivityAndInternetAccess.NetworkState state) {
        // El observador entrega un único snapshot coherente. No mezclarlo con
        // señales transitorias (como intentos pendientes) para pintar la cabecera.
        boolean transportConnected = state != null
                ? state.isConnected() && ConnectivityAndInternetAccess.hasPhysicalNetwork(this)
                : ConnectivityAndInternetAccess.isConnected(this)
                        && ConnectivityAndInternetAccess.hasPhysicalNetwork(this);
        boolean isWifi = ConnectivityAndInternetAccess.isConnectedWifi(this);
        boolean isMobile = ConnectivityAndInternetAccess.isConnectedMobile(this);
        boolean isVpn = ConnectivityAndInternetAccess.vpnActive(this);
        boolean isAirplane = ConnectivityAndInternetAccess.isAirplaneModeOn(this);
        boolean isFast = ConnectivityAndInternetAccess.isConnectedFast(this);
        boolean isCaptive = state != null
                ? state.isCaptivePortalDetected()
                : ConnectivityAndInternetAccess.isCaptivePortalDetected(this);
        boolean isValidated = state != null && state.isInternetValidated();
        boolean validationSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
        boolean isConnected = transportConnected
                && (!validationSupported || isValidated || isVpn)
                && ConnectivityAndInternetAccess.isConnected(this);

        Log.d(TAG, "Chequeo de red: Transport=" + transportConnected +
                ", InternetReady=" + isConnected +
                ", Wifi=" + isWifi + ", Mobile=" + isMobile +
                ", VPN=" + isVpn + ", Airplane=" + isAirplane + ", Fast=" + isFast);

        if (isCaptive) {
            // Captive Portal
            viewNetworkDot.setBackgroundResource(R.color.status_warning);
            tvNetworkStatusText.setText("Portal Cautivo");
            tvNetworkStatusText.setTextColor(getResources().getColor(R.color.status_warning));

            bannerNetworkNotice.setVisibility(View.VISIBLE);
            bannerNetworkNotice.setBackgroundResource(R.color.status_warning_bg);
            tvBannerText.setText("Se requiere inicio de sesión en red (Portal Cautivo detectado).");
        } else if (!transportConnected) {
            // Disconnected / Offline
            viewNetworkDot.setBackgroundResource(R.color.status_offline);
            tvNetworkStatusText.setText(isAirplane ? "Modo Avión" : "Sin red");
            tvNetworkStatusText.setTextColor(getResources().getColor(R.color.status_offline));

            bannerNetworkNotice.setVisibility(View.VISIBLE);
            bannerNetworkNotice.setBackgroundResource(R.color.status_offline_bg);
            tvBannerText.setText(isAirplane ?
                    "Modo Avión activado. Mostrando noticias guardadas en caché." :
                    "Dispositivo sin conexión a internet. Mostrando noticias guardadas en caché.");
        } else if (!isConnected) {
            // Connected without validated internet
            viewNetworkDot.setBackgroundResource(R.color.status_warning);
            tvNetworkStatusText.setText("Sin Internet");
            tvNetworkStatusText.setTextColor(getResources().getColor(R.color.status_warning));

            bannerNetworkNotice.setVisibility(View.VISIBLE);
            bannerNetworkNotice.setBackgroundResource(R.color.status_warning_bg);
            tvBannerText.setText("Hay una red disponible, pero Internet no está verificado.");
        } else {
            // Fully connected & validated
            viewNetworkDot.setBackgroundResource(R.color.status_online);

            String statusType = "Online";
            if (isVpn) {
                statusType = "Online (VPN)";
            } else if (isWifi) {
                statusType = "Online (Wi-Fi)";
            } else if (isMobile) {
                statusType = isFast ? "Online (4G/5G)" : "Online (Móvil Lento)";
            }
            tvNetworkStatusText.setText(statusType);
            tvNetworkStatusText.setTextColor(getResources().getColor(R.color.status_online));

            bannerNetworkNotice.setVisibility(View.GONE);
        }
    }

    private void cargarNoticiasIniciales() {
        // Cargar desde caché offline primero para renderizado instantáneo
        ArrayList<NoticiaRSS> cached = NewsCacheManager.loadNewsFromCache(this);
        if (cached != null && !cached.isEmpty()) {
            prepararPaginacion(cached);
            layoutEmptyState.setVisibility(View.GONE);
            rvNoticias.setVisibility(View.VISIBLE);
        }

        // Luego lanzar la descarga del RSS
        ejecutarDescargarNoticias();
    }

    private void ejecutarDescargarNoticias() {
        // Guard barato: no iniciar una operación remota si Android no expone
        // una red utilizable. La petición RSS real es la prueba principal.
        if (remoteOperationInFlight) {
            return;
        }
        if (!RemoteOperationPolicy.canStartRemoteRequest(
                hayInternetUtilizable())) {
            mostrarToastConectividad(getString(R.string.network_no_connection));
            usarNoticiasOffline();
            return;
        }

        remoteOperationInFlight = true;
        lastRssFailure = DescargaNoticiasRSS.FailureType.NONE;
        swipeRefreshLayout.setRefreshing(true);

        // El RSS real sigue redirects y cambios de infraestructura; no lo
        // precedemos con otro GET genérico que duplicaría la operación.
        new DescargaNoticiasRSS(MainActivity.this, MainActivity.this, true,
                new DescargaNoticiasRSS.ErrorCallback() {
            @Override
            public void onError(DescargaNoticiasRSS.FailureType type, int httpStatus, Exception exception) {
                lastRssFailure = type;
                if (RemoteOperationPolicy.shouldDiagnoseAfterFailure(
                        false, type == DescargaNoticiasRSS.FailureType.AMBIGUOUS_CONNECTIVITY)) {
                    clasificarFalloRssConDiagnostico();
                }
            }
        }).execute(RSS_URL, NoticiaRSS.RSS_MUY_INTERESANTE);
    }

    /** Añade el siguiente bloque del feed ya descargado sin bloquear la interfaz. */
    private void cargarMasNoticias() {
        if (isLoadingMore || !hasMoreNews || adapter == null) {
            return;
        }

        isLoadingMore = true;
        int end = Math.min(nextNewsIndex + NEWS_PAGE_SIZE, newsPool.size());
        ArrayList<NoticiaRSS> nextPage = new ArrayList<>(newsPool.subList(nextNewsIndex, end));
        nextNewsIndex = end;
        int added = adapter.appendData(nextPage);
        hasMoreNews = nextNewsIndex < newsPool.size();
        isLoadingMore = false;
        NewsCacheManager.saveNewsToCache(this, adapter.getAllData());
        Log.d(TAG, "Scroll infinito: bloque cargado con " + added + " noticias nuevas (" +
                nextNewsIndex + "/" + newsPool.size() + ").");
    }

    private void usarNoticiasOffline() {
        ArrayList<NoticiaRSS> cached = NewsCacheManager.loadNewsFromCache(this);
        if (cached != null && !cached.isEmpty()) {
            prepararPaginacion(cached);
            layoutEmptyState.setVisibility(View.GONE);
            rvNoticias.setVisibility(View.VISIBLE);
        } else {
            rvNoticias.setVisibility(View.GONE);
            layoutEmptyState.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onRecibeNoticiasRSS(ArrayList<NoticiaRSS> listaNoticias) {
        remoteOperationInFlight = false;
        swipeRefreshLayout.setRefreshing(false);

        if (listaNoticias != null && !listaNoticias.isEmpty()) {
            prepararPaginacion(listaNoticias);
            NewsCacheManager.saveNewsToCache(this, listaNoticias);
            layoutEmptyState.setVisibility(View.GONE);
            rvNoticias.setVisibility(View.VISIBLE);

            // Una actualización completa reinicia el recorrido del archivo.
            nextArchivePage = 2;
            isLoadingMore = false;
            consecutiveDuplicatePages = 0;

            Log.d(TAG, "Noticias recibidas con éxito: " + listaNoticias.size());
        } else {
            if (lastRssFailure == DescargaNoticiasRSS.FailureType.HTTP_ERROR) {
                Toast.makeText(this, "El feed de noticias no está disponible ahora.", Toast.LENGTH_SHORT).show();
            } else if (lastRssFailure != DescargaNoticiasRSS.FailureType.AMBIGUOUS_CONNECTIVITY
                    && lastRssFailure != DescargaNoticiasRSS.FailureType.NO_NETWORK) {
                Toast.makeText(this, "No se pudieron obtener nuevas noticias del canal RSS", Toast.LENGTH_SHORT).show();
            }
            usarNoticiasOffline();
        }
    }

    private boolean hayInternetUtilizable() {
        // Una VPN como AdGuard puede transportar Internet correctamente sin
        // aparecer como VALIDATED en NetworkCapabilities. isConnected() es el
        // guard barato; la petición RSS real sigue siendo la prueba definitiva.
        return RemoteOperationPolicy.canStartRemoteRequest(
                ConnectivityAndInternetAccess.isConnected(this),
                ConnectivityAndInternetAccess.hasPhysicalNetwork(this));
    }

    private void clasificarFalloRssConDiagnostico() {
        ConnectivityAndInternetAccess connectivity =
                new ConnectivityAndInternetAccess.Builder().build();
        connectivity.checkInternetAsync(this, new ConnectivityAndInternetAccess.InternetCallback() {
            @Override
            public void onResult(ConnectivityAndInternetAccess.InternetResult result) {
                boolean generalInternet = result != null && result.isReachable();
                if (generalInternet) {
                    mostrarToastConectividad(getString(R.string.network_backend_unavailable));
                } else {
                    mostrarToastConectividad(getString(R.string.network_internet_unavailable));
                }
            }
        });
    }

    private void mostrarToastConectividad(String message) {
        if (message == null || message.equals(lastConnectivityToast)) {
            return;
        }
        lastConnectivityToast = message;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void prepararPaginacion(ArrayList<NoticiaRSS> noticias) {
        newsPool.clear();
        newsPool.addAll(noticias);
        nextNewsIndex = Math.min(NEWS_PAGE_SIZE, newsPool.size());
        adapter.updateData(new ArrayList<>(newsPool.subList(0, nextNewsIndex)));
        hasMoreNews = nextNewsIndex < newsPool.size();
        isLoadingMore = false;
    }

    private void ejecutarDiagnosticoRedCompleto() {
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Diagnóstico de Conectividad")
                .setMessage("Ejecutando comprobación avanzada de red y sondeo activo DNS/HTTP...")
                .setPositiveButton("Cerrar", null)
                .show();

        // Sondeo activo DNS/HTTP
        ConnectivityAndInternetAccess.checkInternetAsyncDefault(this, new ConnectivityAndInternetAccess.InternetCallback() {
            @Override
            public void onResult(ConnectivityAndInternetAccess.InternetResult result) {
                if (dialog != null && dialog.isShowing()) {
                    boolean reachable = result != null && result.isReachable();
                    String reachedHost = result != null ? result.getReachedHost() : "Ninguno";
                    long time = result != null ? result.getElapsedMilliseconds() : 0;
                    boolean transportConnected = ConnectivityAndInternetAccess.isConnected(MainActivity.this)
                            && ConnectivityAndInternetAccess.hasPhysicalNetwork(MainActivity.this);
                    boolean isVpn = ConnectivityAndInternetAccess.vpnActive(MainActivity.this);
                    boolean isWifi = ConnectivityAndInternetAccess.isConnectedWifi(MainActivity.this);
                    boolean isMobile = ConnectivityAndInternetAccess.isConnectedMobile(MainActivity.this);
                    boolean isFast = ConnectivityAndInternetAccess.isConnectedFast(MainActivity.this);
                    boolean isAirplane = ConnectivityAndInternetAccess.isAirplaneModeOn(MainActivity.this);

                    StringBuilder sb = new StringBuilder();
                    sb.append("📡 ESTADO DE INTERFAZ DE RED:\n");
                    sb.append("• Estado general: ").append(reachable ? "Conectado" : (transportConnected ? "Red sin Internet" : "Desconectado")).append("\n");
                    sb.append("• Tipo de red: ").append(isWifi ? "Wi-Fi" : (isMobile ? "Móvil / Celular" : "Otra / Ninguna")).append("\n");
                    sb.append("• Velocidad estimada: ").append(isFast ? "Rápida (High Speed)" : "Lenta / Desconocida").append("\n");
                    sb.append("• Red VPN Activa: ").append(isVpn ? "SÍ" : "No").append("\n");
                    sb.append("• Modo Avión: ").append(isAirplane ? "ACTIVADO" : "Desactivado").append("\n\n");

                    sb.append("🔍 PRUEBA ACTIVA DNS/HTTP (GIST):\n");
                    sb.append("• Internet Real: ").append(reachable ? "SÍ (Internet Verificado)" : "NO (Sin Internet)").append("\n");
                    sb.append("• Servidor alcanzado: ").append(reachedHost).append("\n");
                    sb.append("• Latencia de respuesta: ").append(time).append(" ms\n");

                    if (currentNetworkState != null) {
                        sb.append("\n📋 REGISTRO DE RED (NetworkState):\n");
                        sb.append(currentNetworkState.toString());
                    }

                    dialog.setMessage(sb.toString());
                }
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);

        MenuItem searchItem = menu.findItem(R.id.action_buscar);
        if (searchItem != null) {
            SearchView searchView = (SearchView) searchItem.getActionView();
            if (searchView != null) {
                searchView.setQueryHint("Buscar noticia...");
                searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                    @Override
                    public boolean onQueryTextSubmit(String query) {
                        if (adapter != null) {
                            adapter.filter(query);
                        }
                        return true;
                    }

                    @Override
                    public boolean onQueryTextChange(String newText) {
                        if (adapter != null) {
                            adapter.filter(newText);
                        }
                        return true;
                    }
                });
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_actualizar) {
            ejecutarDescargarNoticias();
            return true;
        } else if (id == R.id.action_test_conectividad) {
            ejecutarDiagnosticoRedCompleto();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
