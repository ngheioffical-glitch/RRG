package com.sectorrrg.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sectorrrg.app.ui.RotationScreen
import com.sectorrrg.app.ui.RrgScreen
import com.sectorrrg.app.ui.TodayScreen
import com.sectorrrg.data.RrgRepository
import kotlinx.coroutines.launch

class MainVm(private val repo: RrgRepository) : ViewModel() {

    val snapshot = repo.snapshot
    val state = repo.state
    val signals = repo.signals

    init {
        refresh(force = false)
    }

    fun refresh(force: Boolean) {
        viewModelScope.launch { repo.refresh(force) }
    }

    fun markSeen() = repo.markSignalsSeen()

    class Factory(private val repo: RrgRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainVm(repo) as T
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = (application as RrgApp).repo
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0B0F14),
                    surface = Color(0xFF11161D),
                )
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Root(viewModel(factory = MainVm.Factory(repo)))
                }
            }
        }
    }
}

private enum class Tab(val label: String) {
    TODAY("今日"), ROTATION("輪動"), RRG("RRG")
}

@Composable
private fun Root(vm: MainVm) {
    var tab by remember { mutableStateOf(Tab.TODAY) }
    val snap by vm.snapshot.collectAsState()
    val state by vm.state.collectAsState()
    val signals by vm.signals.collectAsState()
    val ctx = LocalContext.current
    val unseen = signals.count { !it.seen }

    val askNotify = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    val label = if (t == Tab.TODAY && unseen > 0) {
                        t.label + " " + unseen
                    } else {
                        t.label
                    }
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            tab = t
                            if (t == Tab.TODAY) vm.markSeen()
                        },
                        icon = { Text(label, fontSize = 13.sp) },
                    )
                }
            }
        }
    ) { pad ->
        Surface(
            Modifier.padding(pad),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (tab) {
                Tab.TODAY -> TodayScreen(
                    snap, state,
                    onRefresh = { vm.refresh(true) },
                    onOpen = { openSymbol(ctx, it) },
                )
                Tab.ROTATION -> RotationScreen(snap) { openSymbol(ctx, it) }
                Tab.RRG -> RrgScreen(snap, state) { vm.refresh(true) }
            }
        }
    }
}

/**
 * 跳去富途睇該標的。
 *
 * 網址格式由實測確認：futunn.com/hk/stock/{代號}-US
 * （分享連結尾嘅 ?from=share&stockId=... 係富途內部 ID，唔帶都開到。）
 *
 * app scheme 未經確認，所以試兩個常見寫法，全部失敗就退到網址，
 * 再唔得就退到剪貼板 —— 最差情況你自己喺富途搜索框貼上。
 */
private fun openSymbol(ctx: Context, symbol: String) {
    val s = symbol.uppercase()
    val opened = tryOpen(ctx, "futunn://stock/$s-US") ||
        tryOpen(ctx, "futunn://quote/stock?market=us&code=$s") ||
        tryOpen(ctx, "https://www.futunn.com/hk/stock/$s-US")
    if (!opened) copyTicker(ctx, s, "開唔到富途，")
}

private fun tryOpen(ctx: Context, uri: String): Boolean = runCatching {
    ctx.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
}.getOrDefault(false)

private fun copyTicker(ctx: Context, symbol: String, prefix: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ticker", symbol))
    Toast.makeText(ctx, prefix + symbol + " 已複製", Toast.LENGTH_SHORT).show()
}
