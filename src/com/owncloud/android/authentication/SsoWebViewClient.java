/* ownCloud Android client application
 *   Copyright (C) 2012-2013 ownCloud Inc.
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License version 2,
 *   as published by the Free Software Foundation.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.owncloud.android.authentication;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import com.owncloud.android.lib.common.network.NetworkUtils;
import com.owncloud.android.utils.Log_OC;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;


/**
 * Custom {@link WebViewClient} client aimed to catch the end of a single-sign-on process 
 * running in the {@link WebView} that is attached to.
 * 
 * Assumes that the single-sign-on is kept thanks to a cookie set at the end of the
 * authentication process.
 *   
 * @author David A. Velasco
 */
public class SsoWebViewClient extends WebViewClient {
        
    private static final String TAG = SsoWebViewClient.class.getSimpleName();
    
    public interface SsoWebViewClientListener {
        public void onSsoFinished(String sessionCookie);
    }
    
    private Context mContext;
    private Handler mListenerHandler;
    private WeakReference<SsoWebViewClientListener> mListenerRef;
    private String mTargetUrl;
    private String mLastReloadedUrlAtError;
    
    public SsoWebViewClient (Context context, Handler listenerHandler, SsoWebViewClientListener listener) {
        mContext = context;
        mListenerHandler = listenerHandler;
        mListenerRef = new WeakReference<SsoWebViewClient.SsoWebViewClientListener>(listener);
        mTargetUrl = "fake://url.to.be.set";
        mLastReloadedUrlAtError = null;
    }
    
    public String getTargetUrl() {
        return mTargetUrl;
    }
    
    public void setTargetUrl(String targetUrl) {
        mTargetUrl = targetUrl;
    }

    @Override
    public void onPageStarted (WebView view, String url, Bitmap favicon) {
        Log_OC.d(TAG, "onPageStarted : " + url);
        super.onPageStarted(view, url, favicon);
    }
    
    @Override
    public void onFormResubmission (WebView view, Message dontResend, Message resend) {
        Log_OC.d(TAG, "onFormResubMission ");

        // necessary to grant reload of last page when device orientation is changed after sending a form
        resend.sendToTarget();
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return false;
    }
    
    @Override
    public void onReceivedError (WebView view, int errorCode, String description, String failingUrl) {
        Log_OC.e(TAG, "onReceivedError : " + failingUrl + ", code " + errorCode + ", description: " + description);
        if (!failingUrl.equals(mLastReloadedUrlAtError)) {
            view.reload();
            mLastReloadedUrlAtError = failingUrl;
        } else {
            mLastReloadedUrlAtError = null;
            super.onReceivedError(view, errorCode, description, failingUrl);
        }
    }
    
    @Override
    public void onPageFinished (WebView view, String url) {
        Log_OC.d(TAG, "onPageFinished : " + url);
        mLastReloadedUrlAtError = null;
        if (url.startsWith(mTargetUrl)) {
            view.setVisibility(View.GONE);
            CookieManager cookieManager = CookieManager.getInstance();
            final String cookies = cookieManager.getCookie(url);
            Log_OC.d(TAG, "Cookies: " + cookies);
            if (mListenerHandler != null && mListenerRef != null) {
                // this is good idea because onPageFinished is not running in the UI thread
                mListenerHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        SsoWebViewClientListener listener = mListenerRef.get();
                        if (listener != null) {
                        	// Send Cookies to the listener
                            listener.onSsoFinished(cookies);
                        }
                    }
                });
            }
        } 
    }
    
    
    @Override
    public void doUpdateVisitedHistory (WebView view, String url, boolean isReload) {
        Log_OC.d(TAG, "doUpdateVisitedHistory : " + url);
    }
    
    @Override
    public void onReceivedSslError (WebView view, SslErrorHandler handler, SslError error) {
        Log_OC.d(TAG, "onReceivedSslError : " + error);
        // Test 1
        X509Certificate x509Certificate = getX509CertificateFromError(error);
        boolean isKnowServer = false;
        
        if (x509Certificate != null) {
            Log_OC.d(TAG, "------>>>>> x509Certificate " + x509Certificate.toString());
            
            try {
                isKnowServer = NetworkUtils.isCertInKnownServersStore((Certificate) x509Certificate, mContext);
            } catch (KeyStoreException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (NoSuchAlgorithmException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (CertificateException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
         if (isKnowServer) {
             handler.proceed();
         } else {
             
         }
    }
    
    /**
     * Obtain the X509Certificate from SslError
     * @param   error     SslError
     * @return  X509Certificate from error
     */
    public X509Certificate getX509CertificateFromError (SslError error) {
        Bundle bundle = SslCertificate.saveState(error.getCertificate());
        X509Certificate x509Certificate;
        byte[] bytes = bundle.getByteArray("x509-certificate");
        if (bytes == null) {
            x509Certificate = null;
        } else {
            try {
                CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
                Certificate cert = certFactory.generateCertificate(new ByteArrayInputStream(bytes));
                x509Certificate = (X509Certificate) cert;
            } catch (CertificateException e) {
                x509Certificate = null;
            }
        }

//        if (x509Certificate != null) {
//            Log_OC.d(TAG, "------>>>>> x509Certificate " + x509Certificate.toString());
//        }
        
        return x509Certificate;
    }
    
    @Override
    public void onReceivedHttpAuthRequest (WebView view, HttpAuthHandler handler, String host, String realm) {
        Log_OC.d(TAG, "onReceivedHttpAuthRequest : " + host);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest (WebView view, String url) {
        Log_OC.d(TAG, "shouldInterceptRequest : " + url);
        return null;
    }
    
    @Override
    public void onLoadResource (WebView view, String url) {
        Log_OC.d(TAG, "onLoadResource : " + url);   
    }
    
    @Override
    public void onReceivedLoginRequest (WebView view, String realm, String account, String args) {
        Log_OC.d(TAG, "onReceivedLoginRequest : " + realm + ", " + account + ", " + args);
    }
    
    @Override
    public void onScaleChanged (WebView view, float oldScale, float newScale) {
        Log_OC.d(TAG, "onScaleChanged : " + oldScale + " -> " + newScale);
        super.onScaleChanged(view, oldScale, newScale);
    }

    @Override
    public void onUnhandledKeyEvent (WebView view, KeyEvent event) {
        Log_OC.d(TAG, "onUnhandledKeyEvent : " + event);
    }
    
    @Override
    public boolean shouldOverrideKeyEvent (WebView view, KeyEvent event) {
        Log_OC.d(TAG, "shouldOverrideKeyEvent : " + event);
        return false;
    }

}
