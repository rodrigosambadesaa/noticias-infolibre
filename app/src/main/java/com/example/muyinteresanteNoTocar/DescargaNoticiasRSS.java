package com.example.muyinteresanteNoTocar;

import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.util.Log;
import com.example.muyinteresante.R;

import com.example.muyinteresante.util.ConnectivityAndInternetAccess;
import com.example.muyinteresante.util.RemoteOperationPolicy;

/* Parsea un canal RSS y devuelve sus items en un ArrayList */

public class DescargaNoticiasRSS extends AsyncTask<String,Integer,ArrayList<NoticiaRSS>>{

	public enum FailureType { NONE, NO_NETWORK, HTTP_ERROR, AMBIGUOUS_CONNECTIVITY, OTHER }

	public interface ErrorCallback {
		void onError(FailureType type, int httpStatus, Exception exception);
	}

	private Context contexto=null;
	private iNoticiaRSS objetoReceptor=null;
	private ProgressDialog pd=null;
	private boolean mostrarProgreso=true;
	private boolean connectionAttemptStarted=false;
	private ErrorCallback errorCallback;
	private FailureType failureType = FailureType.NONE;
	private int httpStatus = -1;
	private Exception failureException;
	
	
	
	
	public DescargaNoticiasRSS(Context contexto, iNoticiaRSS objetoReceptor){
		this(contexto, objetoReceptor, true);
	}

	/**
	 * Permite reutilizar el descargador para paginación/infinite scroll sin abrir
	 * un ProgressDialog modal cada vez que se solicitan noticias antiguas.
	 */
	public DescargaNoticiasRSS(Context contexto, iNoticiaRSS objetoReceptor, boolean mostrarProgreso){
		this.contexto = contexto;
		this.objetoReceptor = objetoReceptor;
		this.mostrarProgreso = mostrarProgreso;
	}

	public DescargaNoticiasRSS(Context contexto, iNoticiaRSS objetoReceptor,
			boolean mostrarProgreso, ErrorCallback errorCallback){
		this(contexto, objetoReceptor, mostrarProgreso);
		this.errorCallback = errorCallback;
	}


	@Override
	protected void onPreExecute() {
		super.onPreExecute();
		
		if (contexto != null) {
			// El guard debe ejecutarse antes de crear cualquier indicador de progreso.
			// Así una operación iniciada durante una pérdida de red no muestra una
			// descarga que nunca llegará a ejecutarse.
			if (!RemoteOperationPolicy.canStartRemoteRequest(
					ConnectivityAndInternetAccess.isConnected(contexto),
					ConnectivityAndInternetAccess.hasPhysicalNetwork(contexto))) {
				failureType = FailureType.NO_NETWORK;
				Log.w("DescargaNoticiasRSS", "Descarga omitida antes del progreso: no hay red utilizable.");
				return;
			}

			// Registramos inicio de intento de conexión para seguimiento de estado
			ConnectivityAndInternetAccess.beginConnectionAttempt(contexto);
			connectionAttemptStarted = true;
		}
		
		if (mostrarProgreso && contexto != null) {
			pd = new ProgressDialog(contexto);
			pd.setMessage(contexto.getString(R.string.downloading_news));
			pd.setCancelable(true);
			pd.setOnCancelListener(new DialogInterface.OnCancelListener() {
				
				@Override
				public void onCancel(DialogInterface dialog) {
					DescargaNoticiasRSS.this.cancel(true);
				}
			});
			
			pd.show();
		}
	}

	
	@Override
	protected void onCancelled() {
		super.onCancelled();
		
		// Finalizamos intento de conexión
		if (connectionAttemptStarted) {
			ConnectivityAndInternetAccess.endConnectionAttempt();
		}
		
		if (pd!=null) pd.dismiss();
	}
	
	 
	@Override							// Recibe URL y nombre Canal RSS.
	protected ArrayList<NoticiaRSS> doInBackground(String... params) {
		
		InputStream entrada = null;
		
		try{
			if (contexto != null && !RemoteOperationPolicy.canStartRemoteRequest(
					ConnectivityAndInternetAccess.isConnected(contexto),
					ConnectivityAndInternetAccess.hasPhysicalNetwork(contexto))) {
				failureType = FailureType.NO_NETWORK;
				Log.w("DescargaNoticiasRSS", "Descarga omitida: no hay una red utilizable.");
				return null;
			}

			 
			
			 // Creamos objeto URL a partir de la direccion web para conectarnos con el servidor
			URL url = new URL(params[0]);
			URLConnection conex = url.openConnection(); // La petición real es la prueba principal
			// Evitamos bloqueos indefinidos ante pérdidas silenciosas de conectividad
			// o transiciones entre redes que dejen el socket en un agujero negro TCP.
			conex.setConnectTimeout(15000);
			conex.setReadTimeout(30000);
			conex.setUseCaches(false); // Evitamos la cache de datos.
			conex.setRequestProperty("accept", "application/rss+xml, application/xml, text/xml, */*");
			conex.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) noticias-infolibre/1.0");
			 
			 // Abrimos el fichero para su lectura/descarga
			if (conex instanceof HttpURLConnection) {
				httpStatus = ((HttpURLConnection) conex).getResponseCode();
				if (httpStatus < 200 || httpStatus >= 300) {
					failureType = FailureType.HTTP_ERROR;
					return null;
				}
			}
			entrada = conex.getInputStream();	

			final ArrayList<NoticiaRSS> noticias = new ArrayList<>();
            RssPullParser.parse(entrada, item -> {
                if (isCancelled()) return;
                try {
                    noticias.add(NoticiaRSS.fromRssFields(item.title, item.description,
                            item.link, item.pubDate, item.image));
                    publishProgress(noticias.size());
                } catch (Exception exception) {
                    Log.w("DescargaNoticiasRSS", "Noticia RSS inválida", exception);
                }
            });
            if (isCancelled()) return null;
            return noticias;
		}
		catch (Exception e){
			failureException = e;
			failureType = isConnectivityException(e)
					? FailureType.AMBIGUOUS_CONNECTIVITY : FailureType.OTHER;
			Log.w("DescargaNoticiasRSS", "Error descargando RSS (" + failureType + ")", e);
			return null;
		}
		finally {
			if (entrada != null) {
				try {
					entrada.close();
				} catch (Exception ignored) { }
			}
		}

	}
	
	
	@Override
	protected void onPostExecute(ArrayList<NoticiaRSS> result) {
		super.onPostExecute(result);
		
		// Finalizamos intento de conexión
		if (connectionAttemptStarted) {
			ConnectivityAndInternetAccess.endConnectionAttempt();
			connectionAttemptStarted = false;
		}
		
		if (pd!=null) pd.dismiss();
		if (result == null && errorCallback != null) {
			errorCallback.onError(failureType, httpStatus, failureException);
		}
		if (objetoReceptor!=null ) objetoReceptor.onRecibeNoticiasRSS(result);
	}

	static boolean isConnectivityException(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof UnknownHostException
					|| current instanceof ConnectException
					|| current instanceof SocketTimeoutException
					|| current instanceof javax.net.ssl.SSLException
					|| current instanceof java.io.InterruptedIOException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}


	@Override
	protected void onProgressUpdate(Integer... values) {
		super.onProgressUpdate(values);
		if (pd != null && values != null && values.length > 0) {
			pd.setMessage(contexto.getString(R.string.downloading_news_count, values[0]));
		}
	}
}
