package com.cinemind.core.domain.color

/**
 * A creative look defined by colour maths (original to CineMind, so free to use and export):
 * a base grade, split toning (colour pushed into shadows / highlights) and optional mono/sepia.
 */
data class LookRecipe(
    val base: ColorGrade = ColorGrade.NONE,
    /** RGB push added to shadows (scaled by darkness²). */
    val shadowTint: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** RGB push added to highlights (scaled by brightness²). */
    val highlightTint: FloatArray = floatArrayOf(0f, 0f, 0f),
    val mono: Boolean = false,
    /** For mono looks: warm paper tone 0..1. */
    val sepia: Float = 0f,
) {
    fun apply(c: FloatArray) {
        ColorMath.adjust(c, base)
        val l = ColorMath.luma(c[0], c[1], c[2]).coerceIn(0f, 1f)
        val sw = (1f - l) * (1f - l)
        val hw = l * l
        for (i in 0..2) c[i] += shadowTint[i] * sw + highlightTint[i] * hw
        if (mono) {
            val m = ColorMath.luma(c[0], c[1], c[2])
            c[0] = m + sepia * 0.08f; c[1] = m + sepia * 0.02f; c[2] = m - sepia * 0.07f
        }
        for (i in 0..2) c[i] = c[i].coerceIn(0f, 1f)
    }

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** A look in the library. [tags] drive search and AI suggestions (scenes and moods). */
data class BuiltInLook(
    val key: String,
    val name: String,
    val description: String,
    val category: String,
    val tags: Set<String>,
    val recipe: LookRecipe,
) {
    val id: String get() = "builtin:$key"

    fun toLut(size: Int = 33): CubeLut = CubeLut.bake(size, name) { inp, out ->
        out[0] = inp[0]; out[1] = inp[1]; out[2] = inp[2]
        recipe.apply(out)
    }
}

object BuiltInLooks {

    private fun g(
        exp: Float = 0f, con: Float = 0f, sat: Float = 0f, vib: Float = 0f, tmp: Float = 0f, tnt: Float = 0f,
        hi: Float = 0f, sh: Float = 0f, fade: Float = 0f,
    ) = ColorGrade(exposure = exp, contrast = con, saturation = sat, vibrance = vib, temperature = tmp, tint = tnt, highlights = hi, shadows = sh, fade = fade)

    private fun rgb(r: Float, gr: Float, b: Float) = floatArrayOf(r, gr, b)

    private fun look(key: String, name: String, category: String, description: String, tags: String, recipe: LookRecipe) =
        BuiltInLook(key, name, description, category, tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(), recipe)

    val ALL: List<BuiltInLook> = listOf(
        // ---- Cinematic
        look("teal_orange", "Teal & Orange", "Cinematic", "Warm skin and highlights, teal shadows — the blockbuster look.",
            "cinematic, travel, city, sunset, beach, dramatic, warm, cool, portrait, action",
            LookRecipe(g(con = 0.18f, sat = 0.05f, vib = 0.1f), shadowTint = rgb(-0.05f, 0.03f, 0.08f), highlightTint = rgb(0.07f, 0.03f, -0.04f))),
        look("blockbuster", "Blockbuster", "Cinematic", "Punchy contrast with cool shadows.",
            "cinematic, action, sports, dramatic, energetic, city",
            LookRecipe(g(con = 0.3f, sat = -0.05f, sh = -0.1f), shadowTint = rgb(-0.03f, 0.01f, 0.06f), highlightTint = rgb(0.04f, 0.02f, 0f))),
        look("moody_blue", "Moody Blue", "Cinematic", "Cool, muted and calm.",
            "moody, cool, cinematic, rain, night, ocean, calm, winter, indoor",
            LookRecipe(g(con = 0.12f, sat = -0.25f, tmp = -0.35f, fade = 0.15f), shadowTint = rgb(-0.02f, 0.01f, 0.06f))),
        look("bleach_bypass", "Bleach Bypass", "Cinematic", "Silvery, low saturation, hard contrast.",
            "cinematic, dramatic, gritty, war, moody, action, urban",
            LookRecipe(g(con = 0.4f, sat = -0.55f, hi = 0.1f))),
        look("drama", "Desaturated Drama", "Cinematic", "Deep shadows and restrained colour.",
            "dramatic, moody, cinematic, portrait, interview, documentary",
            LookRecipe(g(con = 0.25f, sat = -0.35f, sh = -0.2f, tmp = -0.05f))),
        look("anamorphic_warm", "Anamorphic Warm", "Cinematic", "Soft warm highlights, rich midtones.",
            "cinematic, warm, sunset, golden hour, romantic, travel",
            LookRecipe(g(con = 0.12f, tmp = 0.25f, vib = 0.15f, fade = 0.08f), highlightTint = rgb(0.06f, 0.03f, -0.02f))),
        // ---- Travel & nature
        look("golden_hour", "Golden Hour", "Travel", "Sunlit warmth for sunsets and evenings.",
            "sunset, golden hour, warm, travel, beach, desert, romantic, summer",
            LookRecipe(g(tmp = 0.45f, vib = 0.2f, con = 0.08f, hi = 0.05f), highlightTint = rgb(0.05f, 0.025f, -0.03f))),
        look("tropical", "Tropical Vivid", "Travel", "Saturated blues and greens for sea and sky.",
            "beach, ocean, tropical, travel, summer, vivid, bright, island, water",
            LookRecipe(g(vib = 0.45f, sat = 0.12f, con = 0.12f, tmp = -0.05f))),
        look("alpine", "Alpine Fresh", "Travel", "Crisp, clear and slightly cool.",
            "mountain, snow, winter, nature, travel, cool, clean, sky",
            LookRecipe(g(con = 0.12f, vib = 0.2f, tmp = -0.18f, hi = 0.05f))),
        look("forest", "Forest Green", "Nature", "Deep, rich greens and soft shadows.",
            "forest, nature, green, hiking, garden, park, calm",
            LookRecipe(g(con = 0.1f, sat = 0.08f, tnt = -0.2f, sh = 0.05f), shadowTint = rgb(-0.02f, 0.03f, 0.0f))),
        look("desert", "Desert Warm", "Travel", "Sandy warmth with dusty highlights.",
            "desert, warm, travel, road trip, dry, adventure, rajasthan",
            LookRecipe(g(tmp = 0.4f, sat = -0.05f, con = 0.12f, fade = 0.06f), highlightTint = rgb(0.05f, 0.02f, -0.04f))),
        look("ocean_blue", "Ocean Blue", "Nature", "Clean blues for water and sky.",
            "ocean, water, sky, beach, cool, calm, swimming, boat",
            LookRecipe(g(tmp = -0.2f, vib = 0.3f, con = 0.06f))),
        look("monsoon", "Monsoon", "Nature", "Fresh greens and grey skies.",
            "rain, monsoon, nature, green, moody, cool, india",
            LookRecipe(g(tnt = -0.12f, sat = -0.05f, con = 0.1f, tmp = -0.1f, fade = 0.06f))),
        // ---- Lifestyle & vlog
        look("clean_bright", "Clean Bright", "Lifestyle", "Bright, natural and clean — everyday vlogs.",
            "vlog, lifestyle, bright, clean, indoor, product, tutorial, education, daylight",
            LookRecipe(g(exp = 0.15f, con = 0.05f, vib = 0.12f, hi = -0.05f, sh = 0.08f))),
        look("pastel", "Soft Pastel", "Lifestyle", "Light, airy and gentle.",
            "pastel, soft, lifestyle, fashion, wedding, baby, spring, calm",
            LookRecipe(g(exp = 0.1f, con = -0.15f, sat = -0.15f, fade = 0.2f, tmp = 0.05f), highlightTint = rgb(0.02f, 0f, 0.02f))),
        look("cozy", "Cozy Warm", "Lifestyle", "Warm indoor light for home and cafés.",
            "indoor, cafe, home, warm, cozy, food, festival, family, diwali",
            LookRecipe(g(tmp = 0.3f, con = 0.08f, vib = 0.1f, sh = 0.05f))),
        look("summer_pop", "Summer Pop", "Lifestyle", "Bold, cheerful colour.",
            "summer, vivid, party, festival, holi, fun, energetic, reels, bright",
            LookRecipe(g(vib = 0.4f, sat = 0.2f, con = 0.15f, exp = 0.05f))),
        // ---- Portrait & wedding
        look("romantic", "Romantic Glow", "Wedding", "Soft warm glow for couples and weddings.",
            "wedding, romantic, portrait, couple, warm, soft, love",
            LookRecipe(g(tmp = 0.18f, con = -0.05f, fade = 0.08f, hi = 0.08f, vib = 0.05f), highlightTint = rgb(0.04f, 0.015f, 0.01f))),
        look("ivory", "Ivory Soft", "Wedding", "Creamy whites and gentle contrast.",
            "wedding, bride, portrait, fashion, soft, elegant, white",
            LookRecipe(g(exp = 0.1f, con = -0.1f, sat = -0.1f, tmp = 0.1f, fade = 0.12f))),
        look("skin_warm", "True Skin", "Portrait", "Flattering warm skin tones, natural colour.",
            "portrait, interview, podcast, people, selfie, skin, vlog",
            LookRecipe(g(tmp = 0.12f, con = 0.06f, sat = -0.05f, sh = 0.06f))),
        // ---- Night & city
        look("neon_night", "Neon Night", "Night", "Magenta and cyan glow for city lights.",
            "night, city, neon, party, club, urban, concert, cyberpunk",
            LookRecipe(g(con = 0.2f, sat = 0.15f, tnt = 0.15f), shadowTint = rgb(0.0f, 0.03f, 0.08f), highlightTint = rgb(0.06f, -0.02f, 0.05f))),
        look("urban_cool", "Urban Cool", "Night", "Steely blues for streets and architecture.",
            "city, urban, street, architecture, cool, night, modern",
            LookRecipe(g(con = 0.15f, sat = -0.2f, tmp = -0.25f))),
        look("cyber_teal", "Cyber Teal", "Night", "Teal-heavy futuristic night look.",
            "night, futuristic, tech, city, cool, gaming, cyberpunk",
            LookRecipe(g(con = 0.22f, sat = -0.05f, tmp = -0.3f, tnt = -0.05f), highlightTint = rgb(-0.02f, 0.04f, 0.05f))),
        // ---- Vintage
        look("retro70", "Retro 70s", "Vintage", "Faded warm film with lifted blacks.",
            "vintage, retro, film, warm, nostalgic, old, memories",
            LookRecipe(g(fade = 0.3f, tmp = 0.3f, sat = -0.15f, con = 0.05f), shadowTint = rgb(0.02f, 0.02f, -0.02f))),
        look("faded_film", "Faded Film", "Vintage", "Matte blacks and soft colour.",
            "vintage, film, matte, soft, nostalgic, indie, calm",
            LookRecipe(g(fade = 0.35f, con = -0.1f, sat = -0.2f))),
        look("instant", "Instant Film", "Vintage", "Instant-camera colour: green shadows, warm whites.",
            "vintage, instant, retro, friends, party, memories",
            LookRecipe(g(fade = 0.2f, con = 0.05f, sat = -0.1f), shadowTint = rgb(-0.01f, 0.03f, 0.01f), highlightTint = rgb(0.04f, 0.02f, -0.02f))),
        // ---- Food & sport
        look("appetizing", "Appetizing", "Food", "Warm, rich colour that makes food pop.",
            "food, cooking, recipe, restaurant, warm, vivid, cafe",
            LookRecipe(g(tmp = 0.2f, vib = 0.3f, con = 0.1f, exp = 0.05f))),
        look("punch", "High Contrast Punch", "Sport", "Crunchy contrast for workouts and sport.",
            "sports, fitness, gym, workout, energetic, action, dramatic",
            LookRecipe(g(con = 0.45f, sat = 0.05f, sh = -0.1f, hi = 0.05f))),
        // ---- Black & white
        look("classic_mono", "Classic Mono", "Black & white", "Rich black and white.",
            "black and white, mono, classic, portrait, street, dramatic, documentary",
            LookRecipe(g(con = 0.2f), mono = true)),
        look("high_key_mono", "High-Key Mono", "Black & white", "Bright, airy black and white.",
            "black and white, mono, bright, fashion, minimal",
            LookRecipe(g(exp = 0.2f, con = -0.1f, sh = 0.15f), mono = true)),
        look("sepia", "Sepia", "Black & white", "Warm toned monochrome.",
            "sepia, vintage, mono, old, nostalgic, heritage",
            LookRecipe(g(con = 0.1f, fade = 0.1f), mono = true, sepia = 1f)),
    )

    fun byKey(key: String): BuiltInLook? = ALL.firstOrNull { it.key == key }
    fun byId(id: String): BuiltInLook? = if (id.startsWith("builtin:")) byKey(id.removePrefix("builtin:")) else null
}
