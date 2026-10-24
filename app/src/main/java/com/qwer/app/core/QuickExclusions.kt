package com.qwer.app.core

/**
 * "Быстрые исключения" — a one-tap list of apps that commonly break or misbehave when
 * their traffic goes through a VPN.
 *
 * WHY THIS EXISTS
 * ---------------
 * Choosing 40 packages by hand from a list of 120 is tedious and most users give up in
 * the middle, which leaves the feature unused. The list below is the well-known set:
 * Russian government services, banks and payment systems, marketplaces, telecom
 * operator apps and streaming — the categories that either block foreign IPs or refuse
 * to work over a tunnel.
 *
 * IMPORTANT BEHAVIOURAL NOTE, stated in the UI as well: these apps only matter in
 * "Все кроме" (blacklist) mode. In "Только выбранные" mode the list has the opposite
 * meaning — everything not listed is excluded — so the button is offered only where it
 * does what the user expects.
 *
 * The list of package names is derived from WDTT Plus (`ui/QuickExclusions.kt`, GPLv3),
 * which is compatible with this project's GPLv3 licence. Keywords are matched against
 * the app label and package name so that renamed or re-signed builds are still caught.
 *
 * Nothing here is installed-dependent: a package that is absent simply never matches.
 */
object QuickExclusions {

    /** Exact package names of apps that commonly need a direct connection. */
    val exactPackages: Set<String> = setOf(
        // Государственные и социальные сервисы
        "ru.rostel",
        "ru.gosuslugi.goskey",
        "com.octopod.russianpost.client.android",
        "ru.crptech.mark",

        // Банки и платежи
        "ru.sberbankmobile",
        "ru.tinkoff.mb",
        "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android",
        "logo.com.mbanking",
        "ru.mts.bank",
        "ru.lewis.dbo",
        "ru.gazprombank.android.mobilebank.app",
        "ru.nspk.mirpay",
        "ru.nspk.sbpay",

        // Соцсети, связь и магазины приложений
        "com.vkontakte.android",
        "com.vk.calls",
        "com.vk.im",
        "com.vk.vkvideo",
        "ru.vk.music",
        "ru.ok.android",
        "ru.oneme.app",
        "ru.mail.mailapp",
        "ru.mail.cloud",
        "ru.vk.store",

        // Маркетплейсы, объявления и работа
        "ru.ozon.app.android",
        "com.wildberries.ru",
        "ru.beru.android",
        "ru.megamarket",
        "ru.sbermarket",
        "ru.instamart",
        "ru.samokat.android",
        "com.avito.android",
        "ru.domclick.mortgage",
        "ru.hh.android",

        // Яндекс
        "ru.yandex.searchplugin",
        "ru.yandex.yandexmaps",
        "ru.yandex.music",
        "ru.kinopoisk",
        "com.yandex.browser",
        "ru.yandex.taxi",

        // Операторы связи
        "ru.mts",
        "ru.beeline.services",
        "ru.tele2.mytele2",
        "ru.megafon.mlk",

        // Магазины, доставка и рестораны
        "ru.vkusvill",
        "com.icemobile.lenta.prod",
        "ru.reksoft.okey",
        "ru.myauchan.droid",
        "www.metro.com",
        "ru.tander.magnit",
        "ru.pyaterochka.app.browser",
        "ru.perekrestok.app",
        "club.chizhik",
        "com.logistic.sdek",
        "ru.dodopizza.app",
        "ru.burgerking",
        "com.apegroup.mcdonaldsrussia",

        // Транспорт, навигация и путешествия
        "ru.dublgis.dgismobile",
        "ru.rzd.pass",
        "ru.tutu.tutu_emp",
        "com.carshering",
        "youdrive.today",
        "com.belkacar",
        "ru.aeroflot",
        "com.taxsee.taxsee",
        "ru.maximoff.max",

        // Видео, музыка, новости и погода
        "ru.rutube.app",
        "ru.ivi.client",
        "ru.more.play",
        "ru.zen.android",
        "gpm.tnt_premier",
        "ru.mts.mtstv",
        "ru.radioplayer",
        "com.gismeteo.client",
    )

    /**
     * Keywords matched against "package name + app label".
     *
     * Needed because the same bank can ship several package variants, and because a
     * store build may be re-signed. Lower-case comparison only; Cyrillic is included on
     * purpose — labels are localised.
     */
    private val keywords: List<String> = listOf(
        "sber", "сбер",
        "tinkoff", "t-bank", "tbank", "тинькофф", "т-банк", "тбанк",
        "vtb", "втб",
        "alfa", "alpha", "альфа",
        "psbank", "промсвязьбанк", "псб",
        "gazprombank", "газпромбанк",
        "mir pay", "mirpay", "мир пэй",
        "sbpay", "сбпэй",
        "ozon", "озон",
        "wildberries", "вайлдберриз",
        "yandex", "яндекс",
        "vkontakte", "вконтакте",
        "rutube", "рутуб",
        "gosuslugi", "госуслуг", "госключ",
        "честный знак",
        "pochta", "почта россии",
        "mts", "мтс",
        "megafon", "мегафон",
        "beeline", "билайн",
        "tele2", "теле2",
    )

    /**
     * True when an installed app should be offered by the quick-exclusion action.
     *
     * @param label user-visible app name (localised)
     * @param packageName manifest package
     */
    fun matches(label: String, packageName: String): Boolean {
        val pkg = packageName.lowercase()
        if (pkg in exactPackages) return true
        val haystack = "$pkg ${label.lowercase()}"
        return keywords.any { occursAtWordStart(haystack, it) }
    }

    /**
     * Matches [token] only where a word or a package segment starts.
     *
     * WHY NOT PLAIN contains(): a bare "mts" would match "com.admtsx.tool", and the
     * user would silently lose an unrelated app from the tunnel.
     *
     * WHY NOT WHOLE-WORD MATCHING EITHER: that rejected `ru.sberbank.online` for the
     * token "sber", because "sberbank" is one word — a real bank app refused to be
     * excluded. The token carries the brand, the segment carries the rest of the name,
     * so a boundary at the START is what matters. Purely ambiguous tokens that would
     * only ever match by prefix ("mir" against "mirror", "мир" against "мирный") are
     * deliberately absent from the keyword list and covered by exact packages instead.
     */
    private fun occursAtWordStart(haystack: String, token: String): Boolean {
        var from = 0
        while (true) {
            val at = haystack.indexOf(token, from)
            if (at < 0) return false
            val boundary = at == 0 || !haystack[at - 1].isLetterOrDigit()
            if (boundary) return true
            from = at + 1
        }
    }

    /**
     * Filters a list of installed apps down to the quick-exclusion candidates,
     * preserving the caller's order.
     */
    fun filter(candidates: List<Pair<String, String>>): List<Pair<String, String>> =
        candidates.filter { (label, pkg) -> matches(label, pkg) }
}
