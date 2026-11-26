package io.pdfoundry.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import io.pdfoundry.app.data.DocumentStore
import io.pdfoundry.app.data.PdfEngine
import io.pdfoundry.app.data.Settings
import io.pdfoundry.app.data.SignatureStore
import io.pdfoundry.app.ui.LibraryScreen
import io.pdfoundry.app.ui.PDFoundryTheme
import io.pdfoundry.app.ui.ReaderScreen
import io.pdfoundry.app.ui.SettingsScreen
import kotlinx.coroutines.launch

class PdfApp : Application() {
    lateinit var store: DocumentStore
        private set
    lateinit var signatures: SignatureStore
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        PdfEngine.init(this)
        store = DocumentStore(this)
        signatures = SignatureStore(this)
        settings = Settings(this)
    }
}

val Context.app: PdfApp get() = applicationContext as PdfApp

class MainActivity : ComponentActivity() {
    /** A PDF handed to the app by another app, waiting to be imported and opened. */
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incoming = pdfUri(intent)
        setContent {
            PDFoundryTheme(app.settings.appearance) {
                AppRoot(incoming) { incoming = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming = pdfUri(intent)
    }

    private fun pdfUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}

@Composable
private fun AppRoot(incoming: Uri?, onIncomingHandled: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = context.app.store
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openDocument by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { store.start() }
    LaunchedEffect(incoming) {
        if (incoming != null) {
            store.import(listOf(incoming)).firstOrNull()?.let {
                tab = 0
                openDocument = it.nameWithoutExtension
            }
            onIncomingHandled()
        }
    }

    val open = openDocument
    if (open != null) {
        val close = {
            openDocument = null
            scope.launch { store.refresh() }
            Unit
        }
        BackHandler(onBack = close)
        ReaderScreen(file = store.file(open), onBack = close)
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Filled.Description, null) }, label = { Text("Documents") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        }
    ) { padding ->
        Box(Modifier.padding(bottom = padding.calculateBottomPadding())) {
            if (tab == 0) LibraryScreen(onOpen = { openDocument = it.name }) else SettingsScreen()
        }
    }
}
