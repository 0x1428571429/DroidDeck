package com.droiddeck.launcher.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.store.gog.GogApi
import com.droiddeck.launcher.store.gog.GogManager
import com.droiddeck.launcher.store.gog.GogState

// The Store's GOG side: sign in, the account's Windows games, and installing them into a GOG
// folder the Added Games scan reads - so each shows in Steam as a non-Steam game, launched by
// Steam through Proton like any other added game. gogdl (Heroic's) does the downloading.

/** GOG's own sign-in page; it hands back a code on embed.gog.com, which gogdl trades for tokens. */
private const val GOG_LOGIN = "https://auth.gog.com/auth?client_id=46899977096215655" +
    "&redirect_uri=https%3A%2F%2Fembed.gog.com%2Fon_login_success%3Forigin%3Dclient&response_type=code&layout=galaxy"

@Composable
internal fun GogStore(s: FrontEndState) {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var signingIn by remember { mutableStateOf(false) }
    LaunchedEffect(s.ready) { GogState.refresh(ctx) }
    BackHandler(enabled = open != null) { open = null }
    if (signingIn) GogLoginDialog(onCode = { signingIn = false; GogState.login(ctx, it) }, onDismiss = { signingIn = false })
    if (!s.ready) {
        Rise(1) { Note("Install the Linux runtime from Setup first; GOG games install into it.") }
        return
    }
    val id = open
    if (id != null) {
        GogDetail(id) { open = null }
        return
    }
    GogBusyBar()
    when {
        !GogState.ready -> Rise(1) {
            GogCard(
                "Set up GOG",
                "Adds gogdl, the GOG downloader Heroic Games Launcher uses (about 2 MB), to the Linux runtime. " +
                    "Games you own on GOG then install from here and show in Steam's library as non-Steam games.",
            ) {
                PrimaryButton(if (GogState.busy == "setup") "Setting up…" else "Set up GOG", enabled = GogState.busy == null, main = true) { GogState.setup(ctx) }
            }
        }
        !GogState.signedIn -> Rise(1) {
            GogCard("Sign in to GOG", "Sign in with your GOG account to list and install the games you own. The sign-in stays in the app's private storage.") {
                PrimaryButton(if (GogState.busy == "login") "Signing in…" else "Sign in", enabled = GogState.busy == null, main = true) { signingIn = true }
            }
        }
        else -> GogLibrary(onOpen = { open = it }, onSignOut = {
            GogState.signOut(ctx)
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        })
    }
}

@Composable
private fun GogCard(title: String, body: String, actions: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(Shape16).background(colors.surface).border(1.dp, pal.line, Shape16).padding(16.dp),
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
        Text(body, fontSize = 14.sp, color = colors.onSurfaceVariant)
        Actions { actions() }
    }
}

/** The running setup, sign-in or download, whichever page it was started from. */
@Composable
private fun GogBusyBar() {
    val colors = MaterialTheme.colorScheme
    val busy = GogState.busy ?: return
    val name = GogState.library?.firstOrNull { it.id == busy }?.title ?: GogState.installed.firstOrNull { it.id == busy }?.name
        ?: when (busy) { "setup" -> "GOG"; "login" -> "GOG"; else -> busy }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
        val stage = GogState.stage
        Text(
            "$name · " + (if (stage != null && GogState.percent >= 0) "$stage · ${GogState.percent}%" else stage ?: "Starting…"),
            fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (GogState.percent >= 0) LinearProgressIndicator(progress = { GogState.percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
    }
}

@Composable
private fun GogLibrary(onOpen: (String) -> Unit, onSignOut: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { GogState.loadLibrary(ctx) }
    val library = GogState.library
    val installed = GogState.installed
    Rise(2) {
        Actions {
            SecondaryButton(if (GogState.loadingLibrary) "Refreshing…" else "Refresh", enabled = !GogState.loadingLibrary) { GogState.loadLibrary(ctx, force = true) }
            SecondaryButton("Sign out", enabled = GogState.busy == null, onClick = onSignOut)
        }
    }
    if (installed.isNotEmpty()) {
        Rise(3) { SectionTitle("Installed", installed.size.toString()) }
        Rise(3) {
            GogGrid(installed.map { i ->
                Triple(i.id, i.name, library?.firstOrNull { it.id == i.id }?.image)
            }, first = true, status = { gid -> installed.firstOrNull { it.id == gid }?.let { if (it.complete) "Installed · ${it.base.label}" else "Download stopped" } }, onOpen = onOpen)
        }
        Rise(3) { Note("Installed games show in Steam's library as non-Steam games the next time Steam starts.") }
    }
    when {
        library == null && GogState.libraryFailed -> Rise(4) { Note("GOG could not be reached, or the sign-in has expired. Refresh, or sign out and in again.") }
        library == null -> Rise(4) { SectionTitle("Your games", "loading…") }
        library.isEmpty() -> Rise(4) { Note("No Windows games on this GOG account yet.") }
        else -> {
            Rise(4) { SectionTitle("Your games", library.size.toString()) }
            Rise(5) {
                GogGrid(library.map { Triple(it.id, it.title, it.image) }, first = installed.isEmpty(),
                    status = { gid -> installed.firstOrNull { it.id == gid }?.let { if (it.complete) "Installed" else "Download stopped" } }, onOpen = onOpen)
            }
        }
    }
}

/** Games as tiles with GOG's wide art: three across, or one on a narrow page. */
@Composable
private fun GogGrid(games: List<Triple<String, String, String?>>, first: Boolean, status: (String) -> String?, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val columns = if (LocalNarrowPane.current) 1 else 3
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        games.chunked(columns).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { i, (id, title, image) ->
                    key(id) {
                        val src = remember { MutableInteractionSource() }
                        val hot = rememberHot(src)
                        Column(
                            modifier = Modifier.weight(1f).paneItem("tile:gog:$id").then(if (first && r == 0 && i == 0) Modifier.firstTile() else Modifier)
                                .clip(Shape14).background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
                                .glideBorder(hot, Shape14, pal.signal, pal.line)
                                .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button) { onOpen(id) },
                        ) {
                            AsyncImage(
                                model = image, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(colors.surfaceVariant),
                            )
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val st = status(id)
                                Text(st ?: "GOG", fontSize = 13.sp, color = if (st != null) pal.good else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun GogDetail(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val narrow = LocalNarrowPane.current
    val game = GogState.library?.firstOrNull { it.id == id }
    val local = GogState.installed.firstOrNull { it.id == id }
    val title = game?.title ?: local?.name ?: id
    val bases = remember { GogManager.bases(ctx) }
    var baseId by rememberSaveable(id) { mutableStateOf(bases.first().id) }
    LaunchedEffect(id) { GogState.loadSizes(ctx, id) }
    val sizes = GogState.sizes[id]
    val busyHere = GogState.busy == id
    // Uninstall asks twice: one stray press of A should not cost a download.
    var confirmRemove by remember(id) { mutableStateOf(false) }
    LaunchedEffect(confirmRemove) { if (confirmRemove) { kotlinx.coroutines.delay(4000); confirmRemove = false } }
    Rise(0) { BackLink("GOG", onClick = onBack) }
    Rise(1) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)) {
            AsyncImage(
                model = game?.image, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(if (narrow) 140.dp else 220.dp).aspectRatio(16f / 9f).clip(Shape12).background(colors.surfaceVariant),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = if (narrow) 22.sp else 26.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        local?.complete == true -> "Installed on ${local.base.label}"
                        local != null -> "Download stopped on ${local.base.label}"
                        else -> "Windows · runs through Proton in Steam"
                    },
                    fontSize = 14.sp, color = colors.onSurfaceVariant,
                )
            }
        }
    }
    if (local == null && bases.size > 1) {
        Rise(2) { SectionTitle("Install to", null) }
        Rise(2) {
            Actions {
                bases.forEach { b ->
                    val label = "${b.label} · ${GameStorage.free(b.host.parentFile ?: b.host)} free"
                    if (b.id == baseId) PrimaryButton(label, compact = true) {} else SecondaryButton(label, compact = true) { baseId = b.id }
                }
            }
        }
    }
    Rise(3) {
        Actions {
            when {
                busyHere -> SecondaryButton("Stop") { GogState.cancel() }
                local == null || !local.complete -> PrimaryButton(if (local == null) "Install" else "Resume download", main = true, enabled = GogState.busy == null && game != null) {
                    val base = local?.base ?: bases.first { it.id == baseId }
                    game?.let { GogState.install(ctx, it, base) }
                }
                else -> SecondaryButton("Check for update", enabled = GogState.busy == null) { GogState.update(ctx, local) }
            }
            if (local != null && !busyHere) {
                SecondaryButton(if (confirmRemove) "Press again to uninstall" else "Uninstall", enabled = GogState.busy == null) {
                    if (confirmRemove) { confirmRemove = false; GogState.uninstall(ctx, local) } else confirmRemove = true
                }
            }
            sizes?.let {
                ActionChip("${GogManager.formatSize(it.download)} download", ok = false)
                ActionChip("${GogManager.formatSize(it.disk)} installed", ok = false)
            }
            if (local?.complete == true) ActionChip("● Installed", ok = true)
        }
    }
    if (busyHere) GogBusyBar()
    Rise(4) {
        Box(Modifier.padding(top = 12.dp)) {
            Note(
                if (local?.complete == true) "It is in Steam's library as a non-Steam game (after Steam next starts). Uninstalling removes the game's files; " +
                    "its saves and settings stay in its Proton prefix."
                else "Installs the Windows build. Once it finishes, the game shows in Steam's library as a non-Steam game the next time Steam starts, " +
                    "and runs through Proton like the rest of your added games.",
            )
        }
    }
}

/** A sign-in that ends on GOG's success page hands its code to [onCode]; GOG's page does the rest. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GogLoginDialog(onCode: (String) -> Unit, onDismiss: () -> Unit) {
    var done by remember { mutableStateOf(false) }
    fun check(url: String?): Boolean {
        val uri = url?.let(Uri::parse) ?: return false
        if (uri.host != "embed.gog.com" || uri.path?.startsWith("/on_login_success") != true) return false
        val code = uri.getQueryParameter("code") ?: return false
        if (!done) { done = true; onCode(code) }
        return true
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = check(request.url.toString())
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            if (check(url)) view.stopLoading()
                        }
                    }
                    loadUrl(GOG_LOGIN)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
