package com.lucasmunoz.mtg;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.lucasmunoz.mtg.ar.CardArPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // In-app plugins must be registered before the bridge starts in super.onCreate.
        registerPlugin(CardArPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
