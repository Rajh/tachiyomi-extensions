package eu.kanade.tachiyomi.extension.fr.japscan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.apache.commons.lang3.StringUtils
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Japscan : ParsedHttpSource() {

    override val id: Long = 11

    override val name = "Japscan"

    override val baseUrl = "https://www.japscan.co"

    override val lang = "fr"

    override val supportsLatest = true

    private val dicc: HashMap<String, String> = HashMap()
    private val currentScripts: ArrayList<String> = ArrayList()

    override val client: OkHttpClient = network.cloudflareClient.newBuilder().addInterceptor { chain ->
        val indicator = "&decodeImage"

        val request = chain.request()
        val url = request.url().toString()

        val newRequest = request.newBuilder()
            .url(url.substringBefore(indicator))
            .build()
        val response = chain.proceed(newRequest)

        if (!url.endsWith(indicator)) return@addInterceptor response

        val res = response.body()!!.byteStream().use {
            decodeImage(it)
        }

        val rb = ResponseBody.create(MediaType.parse("image/png"), res)
        response.newBuilder().body(rb).build()
    }.build()

    companion object {
        val dateFormat by lazy {
            SimpleDateFormat("dd MMM yyyy", Locale.US)
        }
    }

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/mangas/", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        pageNumberDoc = document

        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }
        val hasNextPage = false
        return MangasPage(mangas, hasNextPage)
    }

    override fun popularMangaNextPageSelector(): String? = null

    override fun popularMangaSelector() = "#top_mangas_week li > span"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("a").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.text()

            val s = StringUtils.stripAccents(it.text())
                .replace("[\\W]".toRegex(), "-")
                .replace("[-]{2,}".toRegex(), "-")
                .replace("^-|-$".toRegex(), "")
            manga.thumbnail_url = "$baseUrl/imgs/mangas/$s.jpg".toLowerCase(Locale.ROOT)
        }
        return manga
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        return GET(baseUrl, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(latestUpdatesSelector())
            .distinctBy { element -> element.select("a").attr("href") }
            .map { element ->
                latestUpdatesFromElement(element)
            }
        val hasNextPage = false
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesSelector() = "#chapters > div > h3.text-truncate"

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isEmpty()) {
            val uri = Uri.parse(baseUrl).buildUpon()
                .appendPath("mangas")
            filters.forEach { filter ->
                when (filter) {
                    is TextField -> uri.appendPath(((page - 1) + filter.state.toInt()).toString())
                    is PageList -> uri.appendPath(((page - 1) + filter.values[filter.state]).toString())
                }
            }
            return GET(uri.toString(), headers)
        } else {
            val formBody = FormBody.Builder()
                .add("search", query)
                .build()
            val searchHeaders = headers.newBuilder()
                .add("X-Requested-With", "XMLHttpRequest")
                .build()
            return POST("$baseUrl/live-search/", searchHeaders, formBody)
        }
    }

    override fun searchMangaNextPageSelector(): String? = "li.page-item:last-child:not(li.active)"

    override fun searchMangaSelector(): String = "div.card div.p-2, a.result-link"

    override fun searchMangaParse(response: Response): MangasPage {
        if ("live-search" in response.request().url().toString()) {
            val body = response.body()!!.string()
            val json = JsonParser().parse(body).asJsonArray
            val mangas = json.map { jsonElement ->
                searchMangaFromJson(jsonElement)
            }

            val hasNextPage = false

            return MangasPage(mangas, hasNextPage)
        } else {
            val document = response.asJsoup()

            val mangas = document.select(searchMangaSelector()).map { element ->
                searchMangaFromElement(element)
            }

            val hasNextPage = searchMangaNextPageSelector()?.let { selector ->
                document.select(selector).first()
            } != null

            return MangasPage(mangas, hasNextPage)
        }
    }

    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        thumbnail_url = element.select("img").attr("abs:src")
        element.select("p a").let {
            title = it.text()
            url = it.attr("href")
        }
    }

    private fun searchMangaFromJson(jsonElement: JsonElement): SManga = SManga.create().apply {
        title = jsonElement["name"].string
        url = jsonElement["url"].string
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div#main > .card > .card-body").first()

        val manga = SManga.create()
        manga.thumbnail_url = "$baseUrl/${infoElement.select(".d-flex > div.m-2:eq(0) > img").attr("src")}"

        infoElement.select(".d-flex > div.m-2:eq(1) > p.mb-2").forEachIndexed { _, el ->
            when (el.select("span").text().trim()) {
                "Auteur(s):" -> manga.author = el.text().replace("Auteur(s):", "").trim()
                "Artiste(s):" -> manga.artist = el.text().replace("Artiste(s):", "").trim()
                "Genre(s):" -> manga.genre = el.text().replace("Genre(s):", "").trim()
                "Statut:" -> manga.status = el.text().replace("Statut:", "").trim().let {
                    parseStatus(it)
                }
            }
        }
        manga.description = infoElement.select("> p").text().orEmpty()

        return manga
    }

    private fun parseStatus(status: String) = when {
        status.contains("En Cours") -> SManga.ONGOING
        status.contains("Terminé") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "#chapters_list > div.collapse > div.chapters_list" +
        ":not(:has(.badge:contains(SPOILER),.badge:contains(RAW),.badge:contains(VUS)))"
    // JapScan sometimes uploads some "spoiler preview" chapters, containing 2 or 3 untranslated pictures taken from a raw. Sometimes they also upload full RAWs/US versions and replace them with a translation as soon as available.
    // Those have a span.badge "SPOILER" or "RAW". The additional pseudo selector makes sure to exclude these from the chapter list.

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select("a").first()

        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.name = urlElement.ownText()
        // Using ownText() doesn't include childs' text, like "VUS" or "RAW" badges, in the chapter name.
        chapter.date_upload = element.select("> span").text().trim().let { parseChapterDate(it) }
        return chapter
    }

    private fun parseChapterDate(date: String): Long {
        return try {
            dateFormat.parse(date)?.time ?: 0
        } catch (e: ParseException) {
            0L
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        var imagePath = "(.*\\/).*".toRegex().find(document.select("#image").attr("data-src"))?.groupValues?.get(1)
        val imageScrambled = if (!document.select("script[src^='/js/iYFbYi_U']").isNullOrEmpty()) "&decodeImage" else ""
        val script = "<script src=\"\\/zjs\\/(.+?)\\.".toRegex().find(document.html())?.groupValues?.get(1)
        generateDictionary(script)

        document.select("select#pages").first()?.select("option")?.forEach {
            if (it.attr("data-img").startsWith("http")) imagePath = ""
            val imgUrl = "$imagePath${it.attr("data-img")}"
            val decodedUrl: String = decodeImgUrl(imgUrl)
            pages.add(Page(pages.size, "", decodedUrl + imageScrambled))
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String = ""

    private fun decodeImage(img: InputStream): ByteArray {
        val input = BitmapFactory.decodeStream(img)

        val xResult = Bitmap.createBitmap(input.width,
            input.height,
            Bitmap.Config.ARGB_8888)
        val xCanvas = Canvas(xResult)

        val result = Bitmap.createBitmap(input.width,
            input.height,
            Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        for (x in 0..input.width step 200) {
            val col1 = Rect(x, 0, x + 100, input.height)
            if ((x + 200) < input.width) {
                val col2 = Rect(x + 100, 0, x + 200, input.height)
                xCanvas.drawBitmap(input, col1, col2, null)
                xCanvas.drawBitmap(input, col2, col1, null)
            } else {
                val col2 = Rect(x + 100, 0, input.width, input.height)
                xCanvas.drawBitmap(input, col1, col1, null)
                xCanvas.drawBitmap(input, col2, col2, null)
            }
        }

        for (y in 0..input.height step 200) {
            val row1 = Rect(0, y, input.width, y + 100)

            if ((y + 200) < input.height) {
                val row2 = Rect(0, y + 100, input.width, y + 200)
                canvas.drawBitmap(xResult, row1, row2, null)
                canvas.drawBitmap(xResult, row2, row1, null)
            } else {
                val row2 = Rect(0, y + 100, input.width, input.height)
                canvas.drawBitmap(xResult, row1, row1, null)
                canvas.drawBitmap(xResult, row2, row2, null)
            }
        }

        val output = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.PNG, 100, output)
        return output.toByteArray()
    }

    // Filters
    private class TextField(name: String) : Filter.Text(name)

    private class PageList(pages: Array<Int>) : Filter.Select<Int>("Page #", arrayOf(0, *pages))

    override fun getFilterList(): FilterList {
        val totalPages = pageNumberDoc?.select("li.page-item:last-child a")?.text()
        val pagelist = mutableListOf<Int>()
        return if (!totalPages.isNullOrEmpty()) {
            for (i in 0 until totalPages.toInt()) {
                pagelist.add(i + 1)
            }
            FilterList(
                Filter.Header("Page alphabétique"),
                PageList(pagelist.toTypedArray())
            )
        } else FilterList(
            Filter.Header("Page alphabétique"),
            TextField("Page #"),
            Filter.Header("Appuyez sur reset pour la liste")
        )
    }

    private var pageNumberDoc: Document? = null

    private fun decodeImgUrl(fakeURL: String): String {
        val point = fakeURL.indexOf("/", 9)
        val origin = fakeURL.substring(0, point)
        val path = fakeURL.substring(point, fakeURL.lastIndexOf("."))
        val ext = fakeURL.substring(fakeURL.lastIndexOf("."))
        val sb = java.lang.StringBuilder(origin)
        for (s in path.split("".toRegex()).toTypedArray()) {
            if (!s.isEmpty()) {
                sb.append(dicc[s])
            }
        }
        sb.append(ext)
        return sb.toString()
    }

    @Synchronized
    private fun generateDictionary(newDicName: String?) {
        if (!currentScripts.contains(newDicName)) {
            val data = client.newCall(Request.Builder().url("https://raw.githubusercontent.com/raulhaag/MiMangaNu/master/js_plugin/28.json").build()).execute().body()?.string()
            val `object` = JSONObject(data)
            val idxs: ArrayList<Int> = ArrayList()
            val values: ArrayList<String> = ArrayList()
            val sources: ArrayList<String> = ArrayList()
            var array: JSONArray = `object`.getJSONArray("idxs")
            for (i in 0 until array.length()) {
                idxs.add(array.getInt(i))
            }
            array = `object`.getJSONArray("values")
            for (i in 0 until array.length()) {
                values.add(array.getString(i))
            }
            array = `object`.getJSONArray("pages")
            for (i in 0 until array.length()) {
                sources.add(array.getString(i))
            }
            val cl: Int = `object`.getInt("length")
            val enc = StringBuilder()
            dicc.clear()
            currentScripts.clear()
            for (web in sources) {
                val data = client.newCall(Request.Builder().url(web).build()).execute().body()?.string()
                if (data != null) {
                    val id = "<script src=\"\\/zjs\\/(.+?)\\.".toRegex().find(data)?.groupValues?.get(1)
                    if (id != null && !currentScripts.contains(id)) {
                        currentScripts.add(id)
                    }
                    val ap = "<div id=\"image\" data-src=\"https://c.japscan.co(/.+?)\\..{3,4}\"".toRegex().find(data)?.groupValues?.get(1)
                    enc.append(ap)
                }
            }
            if (currentScripts.size != 3) {
                throw Exception("Error creating dictionary (ids error, maybe hour change?)")
            }
            if (enc.length == cl) {
                for (i in 0 until idxs.size) {
                    dicc["" + enc[idxs[i]]] = values[i]
                }
            } else {
                throw Exception("Error creating dictionary (not valid length)")
            }
        }
    }
}
