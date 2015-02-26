package uy.edu.fing.proygrad.recallconversation;

import android.os.Handler;
import android.os.Looper;

import com.squareup.otto.Bus;

/**
 * Created by gonzalomelov on 2/23/15.
 */
public class MainThreadBus extends Bus {
  private final Handler handler = new Handler(Looper.getMainLooper());

  @Override public void post(final Object event) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      super.post(event);
    } else {
      handler.post(new Runnable() {
        @Override
        public void run() {
          MainThreadBus.super.post(event);
        }
      });
    }
  }
}