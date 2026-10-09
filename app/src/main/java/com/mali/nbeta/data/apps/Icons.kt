package com.mali.nbeta.data.apps

import com.mali.nbeta.system.DiagLog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.util.LruCache
import android.util.Xml
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mali.nbeta.data.IconShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Everything that changes how an icon looks. Part of every cache key. */
@Immutable
data class IconStyle(
    val shape: IconShape,
    val pack: String?,
    val themed: Boolean,
    val dark: Boolean,
) {
    val id: String = "${shape.ordinal}|${pack.orEmpty()}|${if (themed) (if (dark) "td" else "tl") else "n"}"
}

/**
 * Launcher3-style icon pipeline: render once into a shaped bitmap, persist it to disk, and serve hardware bitmaps
 * from memory. After the first run, every icon is a single decode of a tiny file; nothing touches PackageManager.
 */
class IconRepository(
    private val context: Context,
    /** App key -> "iconPackPackage/drawableName" chosen by the user. */
    private val overrides: () -> Map<String, String> = { emptyMap() },
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val pm = context.packageManager
    private val density = context.resources.displayMetrics.densityDpi
    val sizePx: Int = (64 * context.resources.displayMetrics.density).toInt()
    private val myUser = Process.myUserHandle()
    private val diskDir = File(context.cacheDir, "icons").apply { mkdirs() }
    private val memory = LruCache<String, ImageBitmap>(900)
    private val packs = HashMap<String, IconPack?>()

    /** Icon work is CPU-bound and bursts at startup; cap it so it never starves the UI thread's RenderThread. */
    private val dispatcher = Dispatchers.Default.limitedParallelism(4)

    @Volatile private var activeStyle: String? = null

    private fun key(app: AppEntry, style: IconStyle) = "${app.key}|${app.version}|${style.id}|${overrides()[app.key].orEmpty()}"

    /** One file per app and style; the APK version is a suffix so an update replaces, rather than adds, a file. */
    private fun diskBase(app: AppEntry, style: IconStyle) = hash("${app.key}|${style.id}|${overrides()[app.key].orEmpty()}")

    fun peek(app: AppEntry, style: IconStyle): ImageBitmap? = memory.get(key(app, style))

    suspend fun load(app: AppEntry, style: IconStyle): ImageBitmap = withContext(dispatcher) {
        // Switching shape/pack/theme drops the old set from memory instead of keeping two full sets of bitmaps.
        if (activeStyle != style.id) {
            synchronized(this@IconRepository) {
                if (activeStyle != null && activeStyle != style.id) memory.evictAll()
                activeStyle = style.id
            }
        }
        val k = key(app, style)
        memory.get(k) ?: run {
            val base = diskBase(app, style)
            val file = File(diskDir, "$base-${app.version}")
            (readDisk(file) ?: renderAndStore(file, base) { renderApp(app, style) })
        }.also { memory.put(k, it) }
    }

    /** Warm the memory cache for everything visible on the home screen and in the drawer. */
    suspend fun prewarm(apps: List<AppEntry>, style: IconStyle) = coroutineScope {
        apps.map { app -> async(dispatcher) { runCatching { load(app, style) } } }.awaitAll()
    }

    suspend fun shortcutIcon(info: ShortcutInfo, style: IconStyle): ImageBitmap? = withContext(dispatcher) {
        val k = "sc|${info.`package`}|${info.id}|${info.userHandle.hashCode()}|${info.lastChangedTimestamp}|${style.id}"
        memory.get(k) ?: runCatching {
            val d = launcherApps.getShortcutIconDrawable(info, density) ?: return@withContext null
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            drawShaped(Canvas(bmp), d, style)
            bmp.toHardware().also { memory.put(k, it) }
        }.getOrNull()
    }

    /** Preview of one icon from an icon pack, for the per-app icon picker. */
    suspend fun packIcon(pkg: String, name: String): ImageBitmap? = withContext(dispatcher) {
        val k = "pk|$pkg|$name"
        memory.get(k) ?: runCatching {
            val d = packFor(pkg)?.drawableNamed(name, density) ?: return@withContext null
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            drawFittedPublic(Canvas(bmp), d)
            bmp.toHardware().also { memory.put(k, it) }
        }.getOrNull()
    }

    fun pack(pkg: String): IconPack? = packFor(pkg)

    private fun drawFittedPublic(canvas: Canvas, d: Drawable) = drawFitted(canvas, d, 1f)

    fun clearMemory() = memory.evictAll()

    fun clearAll() {
        memory.evictAll()
        synchronized(packs) { packs.clear() }
        diskDir.listFiles()?.forEach { it.delete() }
    }

    private fun readDisk(f: File): ImageBitmap? {
        if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE }
        return BitmapFactory.decodeFile(f.path, opts)?.asImageBitmap()
    }

    private inline fun renderAndStore(file: File, base: String, render: () -> Bitmap): ImageBitmap {
        val bmp = render()
        try {
            // Older versions of this icon are dead weight.
            diskDir.listFiles { f -> f.name.startsWith("$base-") && f.name != file.name }?.forEach { it.delete() }
            val tmp = File(diskDir, file.name + ".tmp")
            tmp.outputStream().use { out ->
                val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
                bmp.compress(fmt, 100, out)
            }
            tmp.renameTo(file)
        } catch (e: Exception) {
            DiagLog.w(TAG, "Icon cache write failed", e)
        }
        return bmp.toHardware()
    }

    private fun renderApp(app: AppEntry, style: IconStyle): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val custom = overrides()[app.key]?.let { o ->
            packFor(o.substringBefore('/'))?.drawableNamed(o.substringAfter('/'), density)
        }
        val pack = style.pack?.let { packFor(it) }
        val packed = custom ?: pack?.drawableFor(app.component, density)
        val original: Drawable by lazy {
            try {
                launcherApps.resolveActivity(Intent().setComponent(app.component), app.user)?.getIcon(density)
            } catch (_: Exception) {
                null
            } ?: pm.defaultActivityIcon
        }
        when {
            packed != null -> drawFitted(canvas, packed, 1f)
            pack != null && pack.hasBack -> pack.drawWithBack(canvas, original, sizePx, density)
            else -> drawShaped(canvas, original, style)
        }
        return if (app.user != myUser) badge(bmp, app.user) else bmp
    }

    private fun badge(bmp: Bitmap, user: UserHandle): Bitmap {
        val badged = pm.getUserBadgedIcon(BitmapDrawable(context.resources, bmp), user)
        if (badged is BitmapDrawable && badged.bitmap === bmp) return bmp
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        badged.setBounds(0, 0, sizePx, sizePx)
        badged.draw(Canvas(out))
        return out
    }

    fun drawShaped(canvas: Canvas, d: Drawable, style: IconStyle) {
        val s = sizePx
        if (d !is AdaptiveIconDrawable) {
            drawFitted(canvas, d, 0.92f)
            return
        }
        val path = shapePath(style.shape, s)
        // Adaptive layers are 108 units with the central 72 visible, so draw them 1.5x and clip.
        val outer = Rect(-s / 4, -s / 4, s + s / 4, s + s / 4)
        val mono = if (style.themed && Build.VERSION.SDK_INT >= 33) d.monochrome else null
        canvas.save()
        canvas.clipPath(path)
        if (mono != null) {
            val (bg, fg) = themedColors(style.dark)
            canvas.drawColor(bg)
            mono.mutate().setTint(fg)
            mono.bounds = outer
            mono.draw(canvas)
        } else {
            d.background?.let { it.bounds = outer; it.draw(canvas) }
            d.foreground?.let { it.bounds = outer; it.draw(canvas) }
        }
        canvas.restore()
    }

    private fun drawFitted(canvas: Canvas, d: Drawable, scale: Float) {
        val s = sizePx
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: s
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: s
        val f = scale * s / maxOf(iw, ih).toFloat()
        val w = (iw * f).toInt()
        val h = (ih * f).toInt()
        d.setBounds((s - w) / 2, (s - h) / 2, (s + w) / 2, (s + h) / 2)
        d.draw(canvas)
    }

    private fun themedColors(dark: Boolean): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) context.getColor(android.R.color.system_neutral1_800) to context.getColor(android.R.color.system_accent1_100)
        else context.getColor(android.R.color.system_accent1_100) to context.getColor(android.R.color.system_neutral2_700)
    } else {
        if (dark) Color.rgb(0x30, 0x30, 0x36) to Color.rgb(0xD0, 0xE4, 0xFF) else Color.rgb(0xD8, 0xE2, 0xFF) to Color.rgb(0x3A, 0x45, 0x5C)
    }

    fun shapePath(shape: IconShape, s: Int): Path = when (shape) {
        IconShape.System -> AdaptiveIconDrawable(ColorDrawable(Color.BLACK), ColorDrawable(Color.BLACK)).run {
            setBounds(0, 0, s, s)
            Path(iconMask)
        }
        IconShape.Circle -> Path().apply { addCircle(s / 2f, s / 2f, s / 2f, Path.Direction.CW) }
        IconShape.RoundedSquare -> Path().apply { addRoundRect(0f, 0f, s.toFloat(), s.toFloat(), s * 0.24f, s * 0.24f, Path.Direction.CW) }
        IconShape.Teardrop -> Path().apply {
            val r = s / 2f
            val small = s * 0.12f
            addRoundRect(0f, 0f, s.toFloat(), s.toFloat(), floatArrayOf(r, r, r, r, small, small, r, r), Path.Direction.CW)
        }
        IconShape.Squircle -> Path().apply {
            // Superellipse |x|^n + |y|^n = 1 with n = 5 sampled densely enough to be smooth at icon sizes.
            val n = 5.0
            val r = s / 2.0
            val steps = 96
            for (i in 0..steps) {
                val t = 2 * Math.PI * i / steps
                val c = cos(t)
                val sn = sin(t)
                val x = r + r * sign(c) * abs(c).pow(2 / n)
                val y = r + r * sign(sn) * abs(sn).pow(2 / n)
                if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
            }
            close()
        }
    }

    private fun packFor(pkg: String): IconPack? = synchronized(packs) {
        packs.getOrPut(pkg) { IconPack.load(context, pkg) }
    }

    private fun Bitmap.toHardware(): ImageBitmap =
        (copy(Bitmap.Config.HARDWARE, false) ?: this).asImageBitmap()

    private fun hash(k: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(k.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "Icons"
    }
}

/** ADW/Nova-format icon pack: appfilter.xml maps components to drawables, with optional back/upon/scale fallback. */
class IconPack private constructor(
    private val res: Resources,
    private val pkg: String,
    private val map: Map<ComponentName, String>,
    private val backs: List<String>,
    private val upon: String?,
    private val scale: Float,
) {
    val hasBack get() = backs.isNotEmpty()

    fun drawableFor(component: ComponentName, density: Int): Drawable? {
        val name = suggestion(component) ?: return null
        return drawable(name, density)
    }

    /** The pack's own icon for this app, if it has one. */
    fun suggestion(component: ComponentName): String? = map[component] ?: map[ComponentName(component.packageName, "")]

    fun drawableNamed(name: String, density: Int): Drawable? = drawable(name, density)

    /** Every icon the pack offers: drawable.xml when present, otherwise everything appfilter references. */
    val allIcons: List<String> by lazy {
        val names = LinkedHashSet<String>()
        runCatching {
            @Suppress("DiscouragedApi")
            val id = res.getIdentifier("drawable", "xml", pkg)
            val p: XmlPullParser = if (id != 0) res.getXml(id) else Xml.newPullParser().apply { setInput(res.assets.open("drawable.xml"), "UTF-8") }
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "item") p.getAttributeValue(null, "drawable")?.let { names += it }
                ev = p.next()
            }
        }
        if (names.isEmpty()) names += map.values
        names.toList().sorted()
    }

    private fun drawable(name: String, density: Int): Drawable? {
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) return null
        return try {
            res.getDrawableForDensity(id, density, null)
        } catch (_: Exception) {
            null
        }
    }

    fun drawWithBack(canvas: Canvas, original: Drawable, size: Int, density: Int) {
        val back = drawable(backs[abs(original.hashCode()) % backs.size], density)
        back?.setBounds(0, 0, size, size)
        back?.draw(canvas)
        val inner = (size * scale).toInt()
        val off = (size - inner) / 2
        val src = if (original is AdaptiveIconDrawable) {
            val b = Bitmap.createBitmap(inner, inner, Bitmap.Config.ARGB_8888)
            original.setBounds(0, 0, inner, inner)
            original.draw(Canvas(b))
            BitmapDrawable(res, b)
        } else original
        src.setBounds(off, off, off + inner, off + inner)
        src.draw(canvas)
        upon?.let { drawable(it, density) }?.let {
            it.setBounds(0, 0, size, size)
            it.draw(canvas)
        }
    }

    companion object {
        private val ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.gau.go.launcherex.theme",
            "com.anddoes.launcher.THEME",
            "com.teslacoilsw.launcher.THEME",
        )

        /** Installed icon packs as (package, label). */
        fun installed(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            return ACTIONS.flatMap { a -> pm.queryIntentActivities(Intent(a), 0) }
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        }

        fun load(context: Context, pkg: String): IconPack? = try {
            val res = context.packageManager.getResourcesForApplication(pkg)
            val map = HashMap<ComponentName, String>(2048)
            val backs = ArrayList<String>()
            var upon: String? = null
            var scale = 1f

            @Suppress("DiscouragedApi")
            val xmlId = res.getIdentifier("appfilter", "xml", pkg)
            val parser: XmlPullParser = if (xmlId != 0) res.getXml(xmlId) else {
                Xml.newPullParser().apply { setInput(res.assets.open("appfilter.xml"), "UTF-8") }
            }
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (parser.name) {
                    "item" -> {
                        val comp = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (comp != null && drawable != null) {
                            val inner = comp.removePrefix("ComponentInfo{").removeSuffix("}")
                            val cn = ComponentName.unflattenFromString(inner)
                                ?: if (inner.isNotBlank() && !inner.contains('/')) ComponentName(inner, "") else null
                            if (cn != null) map.putIfAbsent(cn, drawable)
                        }
                    }
                    "iconback" -> for (i in 0 until parser.attributeCount) parser.getAttributeValue(i)?.let { backs += it }
                    "iconupon" -> upon = parser.getAttributeValue(null, "img1")
                    "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: 1f
                }
                ev = parser.next()
            }
            IconPack(res, pkg, map, backs, upon, scale.coerceIn(0.3f, 1f))
        } catch (e: Exception) {
            DiagLog.w("IconPack", "Could not load $pkg", e)
            null
        }
    }
}
