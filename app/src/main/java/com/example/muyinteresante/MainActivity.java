package com.example.muyinteresante;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import androidx.lifecycle.ViewModelProvider;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
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

    private NewsViewModel newsState;
    private DescargaNoticiasRSS.FailureType lastRssFailure = DescargaNoticiasRSS.FailureType.NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        newsState = new ViewModelProvider(this).get(NewsViewModel.class);

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
        newsState.remoteOperationInFlight = false; // Peticiones de la Activity anterior no están ligadas a esta instancia.

        // Infinite scroll: cuando el usuario se aproxima al final se solicita la
        // siguiente página del feed oficial, que contiene noticias más antiguas.
        rvNoticias.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy <= 0 || newsState.isLoadingMore || !newsState.hasMoreNews || adapter == null) {
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

        Log.d(TAG, getString(R.string.main_text_01) + transportConnected +
                getString(R.string.main_text_02) + isConnected +
                getString(R.string.main_text_03) + isWifi + getString(R.string.main_text_04) + isMobile +
                getString(R.string.main_text_05) + isVpn + getString(R.string.main_text_06) + isAirplane + getString(R.string.main_text_07) + isFast);

        if (isCaptive) {
            // Captive Portal
            viewNetworkDot.setBackgroundResource(R.color.status_warning);
            tvNetworkStatusText.setText(getString(R.string.main_text_08));
            tvNetworkStatusText.setTextColor(getResources().getColor(R.color.status_warning));

            bannerNetworkNotice.setVisibility(View.VISIBLE);
            bannerNetworkNotice.setBackgroundResource(R.color.status_warning_bg);
            tvBannerText.setText(getString(R.string.main_text_09));
        } else if (!transportConnected) {
            // Disconnected / Offline
            viewNetworkDot.setBackgroundResource(R.color.status_offline);
            tvNetworkStatusText.setText(isAirplane ? getString(R.string.main_text_10) : getString(R.string.main_text_11));
            tvNetworkStatusText.setTextColor(getResources().getColor(R.color.status_offline));

            bannerNetworkNotice.setVisibility(View.VISIBLE);
            bannerNetworkNotice.setBackgroundResource(R.color.status_offline_bg);
            tvBannerText.setText(isAirplane ?
                    getString(R.string.main_text_12) :
                    getString(R.string.main_text_13));
        } else if (!isConnected) {
            // Connected without validated internet
            viewNetworkDot.setBackgroundResource(R.color.status_warning);
            tvNetworkStatusText.setText(getString(R.string.main_text_14));
            tvNetworkStatusText.setTextColor(getResources().getColor(R.color.status_warning));

            bannerNetworkNotice.setVisibility(View.VISIBLE);
            bannerNetworkNotice.setBackgroundResource(R.color.status_warning_bg);
            tvBannerText.setText(getString(R.string.main_text_15));
        } else {
            // Fully connected & validated
            viewNetworkDot.setBackgroundResource(R.color.status_online);

            String statusType = getString(R.string.main_text_16);
            if (isVpn) {
                statusType = getString(R.string.main_text_17);
            } else if (isWifi) {
                statusType = getString(R.string.main_text_18);
            } else if (isMobile) {
                statusType = isFast ? getString(R.string.main_text_19) : getString(R.string.main_text_20);
            }
            tvNetworkStatusText.setText(statusType);
            tvNetworkStatusText.setTextColor(getResources().getColor(R.color.status_online));

            bannerNetworkNotice.setVisibility(View.GONE);
        }
    }

    private void cargarNoticiasIniciales() {
        if (!newsState.newsPool.isEmpty()) {
            int end = Math.min(newsState.nextNewsIndex, newsState.newsPool.size());
            adapter.updateData(new ArrayList<>(newsState.newsPool.subList(0, end)));
            layoutEmptyState.setVisibility(View.GONE);
            rvNoticias.setVisibility(View.VISIBLE);
            return;
        }
        NewsCacheManager.loadNewsFromCache(this, cached -> {
            if (isFinishing() || isDestroyed() || newsState.hasFreshFeed) return;
            if (cached != null && !cached.isEmpty() && adapter.getAllData().isEmpty()) {
                prepararPaginacion(cached);
                layoutEmptyState.setVisibility(View.GONE);
                rvNoticias.setVisibility(View.VISIBLE);
            }
        });
        ejecutarDescargarNoticias();
    }


    private void ejecutarDescargarNoticias() {
        // Guard barato: no iniciar una operación remota si Android no expone
        // una red utilizable. La petición RSS real es la prueba principal.
        if (newsState.remoteOperationInFlight) {
            return;
        }
        if (!RemoteOperationPolicy.canStartRemoteRequest(
                hayInternetUtilizable())) {
            mostrarToastConectividad(getString(R.string.network_no_connection));
            usarNoticiasOffline();
            return;
        }

        newsState.remoteOperationInFlight = true;
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
        if (newsState.isLoadingMore || !newsState.hasMoreNews || adapter == null) {
            return;
        }

        newsState.isLoadingMore = true;
        int end = Math.min(newsState.nextNewsIndex + NEWS_PAGE_SIZE, newsState.newsPool.size());
        ArrayList<NoticiaRSS> nextPage = new ArrayList<>(newsState.newsPool.subList(newsState.nextNewsIndex, end));
        newsState.nextNewsIndex = end;
        int added = adapter.appendData(nextPage);
        newsState.hasMoreNews = newsState.nextNewsIndex < newsState.newsPool.size();
        newsState.isLoadingMore = false;
        NewsCacheManager.saveNewsToCache(this, adapter.getAllData());
        Log.d(TAG, "Scroll infinito: bloque cargado con " + added + " noticias nuevas (" +
                newsState.nextNewsIndex + "/" + newsState.newsPool.size() + ").");
    }

    private void usarNoticiasOffline() {
        if (!newsState.newsPool.isEmpty() || (adapter != null && !adapter.getAllData().isEmpty())) {
            layoutEmptyState.setVisibility(View.GONE);
            rvNoticias.setVisibility(View.VISIBLE);
            return;
        }
        NewsCacheManager.loadNewsFromCache(this, cached -> {
            if (isFinishing() || isDestroyed() || newsState.hasFreshFeed) return;
            if (cached != null && !cached.isEmpty()) {
                prepararPaginacion(cached);
                layoutEmptyState.setVisibility(View.GONE);
                rvNoticias.setVisibility(View.VISIBLE);
            } else {
                rvNoticias.setVisibility(View.GONE);
                layoutEmptyState.setVisibility(View.VISIBLE);
            }
        });
    }


    @Override
    public void onRecibeNoticiasRSS(ArrayList<NoticiaRSS> listaNoticias) {
        newsState.remoteOperationInFlight = false;
        swipeRefreshLayout.setRefreshing(false);

        if (listaNoticias != null && !listaNoticias.isEmpty()) {
            newsState.hasFreshFeed = true;
            prepararPaginacion(listaNoticias);
            NewsCacheManager.saveNewsToCache(this, listaNoticias);
            layoutEmptyState.setVisibility(View.GONE);
            rvNoticias.setVisibility(View.VISIBLE);

            // Una actualización completa reinicia el recorrido del archivo.
            newsState.nextArchivePage = 2;
            newsState.isLoadingMore = false;
            newsState.consecutiveDuplicatePages = 0;

            Log.d(TAG, "Noticias recibidas con éxito: " + listaNoticias.size());
        } else {
            if (lastRssFailure == DescargaNoticiasRSS.FailureType.HTTP_ERROR) {
                Toast.makeText(this, getString(R.string.main_text_21), Toast.LENGTH_SHORT).show();
            } else if (lastRssFailure != DescargaNoticiasRSS.FailureType.AMBIGUOUS_CONNECTIVITY
                    && lastRssFailure != DescargaNoticiasRSS.FailureType.NO_NETWORK) {
                Toast.makeText(this, getString(R.string.main_text_22), Toast.LENGTH_SHORT).show();
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
        newsState.newsPool.clear();
        newsState.newsPool.addAll(noticias);
        newsState.nextNewsIndex = Math.min(NEWS_PAGE_SIZE, newsState.newsPool.size());
        adapter.updateData(new ArrayList<>(newsState.newsPool.subList(0, newsState.nextNewsIndex)));
        newsState.hasMoreNews = newsState.nextNewsIndex < newsState.newsPool.size();
        newsState.isLoadingMore = false;
    }

    private void ejecutarDiagnosticoRedCompleto() {
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.main_text_23))
                .setMessage(getString(R.string.main_text_24))
                .setPositiveButton(getString(R.string.main_text_25), null)
                .show();

        // Sondeo activo DNS/HTTP
        ConnectivityAndInternetAccess.checkInternetAsyncDefault(this, new ConnectivityAndInternetAccess.InternetCallback() {
            @Override
            public void onResult(ConnectivityAndInternetAccess.InternetResult result) {
                if (dialog != null && dialog.isShowing()) {
                    boolean reachable = result != null && result.isReachable();
                    String reachedHost = result != null ? result.getReachedHost() : getString(R.string.main_text_26);
                    long time = result != null ? result.getElapsedMilliseconds() : 0;
                    boolean transportConnected = ConnectivityAndInternetAccess.isConnected(MainActivity.this)
                            && ConnectivityAndInternetAccess.hasPhysicalNetwork(MainActivity.this);
                    boolean isVpn = ConnectivityAndInternetAccess.vpnActive(MainActivity.this);
                    boolean isWifi = ConnectivityAndInternetAccess.isConnectedWifi(MainActivity.this);
                    boolean isMobile = ConnectivityAndInternetAccess.isConnectedMobile(MainActivity.this);
                    boolean isFast = ConnectivityAndInternetAccess.isConnectedFast(MainActivity.this);
                    boolean isAirplane = ConnectivityAndInternetAccess.isAirplaneModeOn(MainActivity.this);

                    StringBuilder sb = new StringBuilder();
                    sb.append(getString(R.string.main_text_27));
                    sb.append(getString(R.string.main_text_28)).append(reachable ? getString(R.string.main_text_29) : (transportConnected ? getString(R.string.main_text_30) : getString(R.string.main_text_31))).append("\n");
                    sb.append(getString(R.string.main_text_32)).append(isWifi ? getString(R.string.main_text_33) : (isMobile ? getString(R.string.main_text_34) : getString(R.string.main_text_35))).append("\n");
                    sb.append(getString(R.string.main_text_36)).append(isFast ? getString(R.string.main_text_37) : getString(R.string.main_text_38)).append("\n");
                    sb.append(getString(R.string.main_text_39)).append(isVpn ? getString(R.string.main_text_40) : getString(R.string.main_text_41)).append("\n");
                    sb.append(getString(R.string.main_text_42)).append(isAirplane ? getString(R.string.main_text_43) : getString(R.string.main_text_44)).append("\n\n");

                    sb.append(getString(R.string.main_text_45));
                    sb.append(getString(R.string.main_text_46)).append(reachable ? getString(R.string.main_text_47) : getString(R.string.main_text_48)).append("\n");
                    sb.append(getString(R.string.main_text_49)).append(reachedHost).append("\n");
                    sb.append(getString(R.string.main_text_50)).append(time).append(getString(R.string.main_text_51));

                    if (currentNetworkState != null) {
                        sb.append(getString(R.string.main_text_52));
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
                searchView.setQueryHint(getString(R.string.main_text_53));
                searchView.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
                searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                    @Override public boolean onMenuItemActionExpand(MenuItem item) {
                        searchView.post(new Runnable() {
                            @Override public void run() {
                                searchView.setIconified(false);
                                searchView.requestFocus();
                                android.view.inputmethod.InputMethodManager ime =
                                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                                if (ime != null) {
                                    View focused = searchView.findFocus();
                                    ime.showSoftInput(focused != null ? focused : searchView,
                                            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                                }
                            }
                        });
                        return true;
                    }
                    @Override public boolean onMenuItemActionCollapse(MenuItem item) {
                        searchView.setQuery("", false);
                        if (adapter != null) adapter.filter("");
                        return true;
                    }
                });
                
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
