package com.payandplan.app.util

/**
 * A face for the products nobody photographs: bread, cold cuts, whatever comes from the
 * counter with no barcode and no picture. The name decides the icon; when nothing matches,
 * the trolley stands in. Keep this list in step with itemFace() in the web app.
 */
object Faces {

    private val TABLE: List<Pair<List<String>, String>> = listOf(
        listOf("prosciutto", "salame", "salsicc", "mortadella", "speck", "bresaola", "wurstel",
            "insaccat", "salumi", "pancetta", "guanciale", "ham", "bacon", "sausage") to "🥓",
        listOf("pane", "panino", "panini", "baguette", "focaccia", "piadina", "bread", "toast") to "🍞",
        listOf("brioche", "cornetto", "croissant") to "🥐",
        listOf("latte", "milk", "panna") to "🥛",
        listOf("formaggio", "parmigiano", "mozzarella", "pecorino", "ricotta", "grana",
            "scamorza", "provola", "cheese", "parmesan") to "🧀",
        listOf("uova", "uovo", "egg") to "🥚",
        listOf("pollo", "tacchino", "chicken") to "🍗",
        listOf("carne", "manzo", "vitello", "maiale", "bistecca", "macinato", "meat", "beef", "pork", "steak") to "🥩",
        listOf("pesce", "tonno", "salmone", "gamber", "vongole", "cozze", "fish") to "🐟",
        listOf("pasta", "spaghetti", "penne", "fusilli", "rigatoni", "gnocchi", "noodle") to "🍝",
        listOf("riso", "rice") to "🍚",
        listOf("pizza") to "🍕",
        listOf("pomodor", "tomato") to "🍅",
        listOf("insalata", "lattuga", "spinaci", "rucola", "salad") to "🥬",
        listOf("patat", "potato") to "🥔",
        listOf("cipoll", "onion") to "🧅",
        listOf("carot", "carrot") to "🥕",
        listOf("broccol", "zucchin", "verdur", "melanzan", "peperon", "vegetable") to "🥦",
        listOf("mela", "mele", "apple") to "🍎",
        listOf("banan") to "🍌",
        listOf("arance", "arancia", "mandarin", "clementin", "orange") to "🍊",
        listOf("limon", "lemon") to "🍋",
        listOf("uva", "grape") to "🍇",
        listOf("fragol", "strawberr") to "🍓",
        listOf("frutta", "fruit", "pesche", "pere", "kiwi") to "🍏",
        listOf("caffe", "caffè", "coffee", "cialde", "capsule") to "☕",
        listOf("tisana", "camomilla", "tea") to "🍵",
        listOf("vino", "wine", "prosecco") to "🍷",
        listOf("birra", "beer") to "🍺",
        listOf("acqua", "water") to "💧",
        listOf("succo", "juice", "aranciata", "cola", "bibit", "the ") to "🧃",
        listOf("biscott", "cookie", "merend", "crackers", "taralli", "biscuit") to "🍪",
        listOf("cioccolat", "chocolate", "nutella", "cacao") to "🍫",
        listOf("gelato", "ghiacciol", "ice cream") to "🍨",
        listOf("torta", "dolc", "crostata", "cake") to "🍰",
        listOf("olio", "oliv", "oil") to "🫒",
        listOf("sale", "salt") to "🧂",
        listOf("zucchero", "sugar", "caramell") to "🍬",
        listOf("farina", "flour", "lievito") to "🌾",
        listOf("miele", "honey", "marmellat", "confettura") to "🍯",
        listOf("yogurt", "yoghurt", "cereali") to "🥣",
        listOf("burro", "butter") to "🧈",
        listOf("detersiv", "detergent", "ammorbid", "candeggi", "lavatrice", "piatti", "sgrassat", "washing powder", "laundry", "dish soap", "bleach") to "🧴",
        listOf("sapone", "shampoo", "bagnoschiuma", "dentifric", "deodorant", "soap", "toothpaste") to "🧼",
        listOf("carta igienica", "scottex", "tovagliol", "fazzolett", "napkin", "toilet") to "🧻",
        listOf("pannolin", "diaper", "assorbent") to "🧷",
        listOf("crocchett", "croccantini", "gatto", "cane", "cat food", "dog food") to "🐾",
        listOf("medicin", "farmac", "tachipirina", "aspirin", "integrator", "pill") to "💊",
        listOf("fiori", "flower", "pianta") to "💐",
        listOf("sacchett", "spazzatura", "immondizia", "rubbish", "bin bag") to "🗑",
        listOf("pila", "batteri", "lampadin", "battery", "bulb") to "🔋"
    )

    private val TROLLEY = "🛒"

    /** Accents off, lower case: "Caffè" and "caffe" have to look the same to the table. */
    private fun plain(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    fun of(text: String?): String {
        val name = plain(text.orEmpty())
        if (name.isBlank()) return TROLLEY
        for ((keys, face) in TABLE) {
            if (keys.any { name.contains(plain(it)) }) return face
        }
        return TROLLEY
    }
}
