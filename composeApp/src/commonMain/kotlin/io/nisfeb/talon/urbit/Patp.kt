package io.nisfeb.talon.urbit

/**
 * Urbit `@p` (ship) helpers. A patp is either a short 3-letter "galaxy"
 * (`~zod`), a 6-letter "star" (`~marzod`), a 6-letter "planet" (`~lodlep`),
 * a 12-char "moon" (`~ragryl-hardus`), or a longer "comet". We use a
 * permissive regex — three-or-six letter syllables joined by dashes —
 * which matches every real ship and rejects obvious garbage.
 */
// Try the 6-letter syllable first. If 3-letter is tried first, `~finned`
// greedily matches as `~fin` (3 letters) and aborts because the next
// char `n` is still a patp-char — so we'd never recognize real stars
// or planets like ~sarlev / ~finned-palmer.
// `--?`: comets join their two halves with a double dash
// (~satnet-rinsyr-silsul-bacnec--todmeb-harwen-fadpem-ribdyr) — a
// single-dash-only pattern locked comets out of every gate using this.
val PATP_REGEX: Regex =
    Regex("~(?:[a-z]{6}|[a-z]{3})(?:--?(?:[a-z]{6}|[a-z]{3}))*")

/**
 * Whether [text] is a real `@p`: the right shape and every syllable
 * from Urbit's phonetic tables. [PATP_REGEX] only checks the shape, so
 * `~wisper` passes it; the ship's own parser rejects such a string and
 * with it the whole post, as a user found when a stray `~word` in a
 * message made every send fail. A 3-letter name is a galaxy (suffix
 * only); 6-letter groups are prefix + suffix.
 */
fun isValidPatp(text: String): Boolean {
    if (!PATP_REGEX.matches(text)) return false
    val groups = text.removePrefix("~").split(Regex("--?"))
    return groups.all { g ->
        when (g.length) {
            3 -> g in SUFFIXES
            6 -> g.substring(0, 3) in PREFIXES && g.substring(3) in SUFFIXES
            else -> false
        }
    }
}

// The 256 prefix and 256 suffix syllables, from `++po` in hoon.hoon.
private val PREFIXES: Set<String> = (
    "dozmarbinwansamlitsighidfidlissogdirwacsabwissibrigsoldopmodfogl" +
            "idhopdardorlorhodfolrintogsilmirholpaslacrovlivdalsatlibtabhanti" +
            "cpidtorbolfosdotlosdilforpilramtirwintadbicdifrocwidbisdasmidlop" +
            "rilnardapmolsanlocnovsitnidtipsicropwitnatpanminritpodmottamtols" +
            "avposnapnopsomfinfonbanmorworsipronnorbotwicsocwatdolmagpicdavbi" +
            "dbaltimtasmalligsivtagpadsaldivdactansidfabtarmonranniswolmispal" +
            "lasdismaprabtobrollatlonnodnavfignomnibpagsopralbilhaddocridmocp" +
            "acravripfaltodtiltinhapmicfanpattaclabmogsimsonpinlomrictapfirha" +
            "sbosbatpochactidhavsaplindibhosdabbitbarracparloddosbortochilmac" +
            "tomdigfilfasmithobharmighinradmashalraglagfadtopmophabnilnosmilf" +
            "opfamdatnoldinhatnacrisfotribhocnimlarfitwalrapsarnalmoslandonda" +
            "nladdovrivbacpollaptalpitnambonrostonfodponsovnocsorlavmatmipfip" +
            ""
    ).chunked(3).toHashSet()

private val SUFFIXES: Set<String> = (
    "zodnecbudwessevpersutletfulpensytdurwepserwylsunrypsyxdyrnuphebp" +
            "eglupdepdysputlughecryttyvsydnexlunmeplutseppesdelsulpedtemledtu" +
            "lmetwenbynhexfebpyldulhetmevruttylwydtepbesdexsefwycburderneppur" +
            "rysrebdennutsubpetrulsynregtydsupsemwynrecmegnetsecmulnymtevwebs" +
            "ummutnyxrextebfushepbenmuswyxsymselrucdecwexsyrwetdylmynmesdetbe" +
            "tbeltuxtugmyrpelsyptermebsetdutdegtexsurfeltudnuxruxrenwytnubmed" +
            "lytdusnebrumtynseglyxpunresredfunrevrefmectedrusbexlebduxrynnump" +
            "yxrygryxfeptyrtustyclegnemfermertenlusnussyltecmexpubrymtucfylle" +
            "pdebbermughuttunbylsudpemdevlurdefbusbeprunmelpexdytbyttyplevmyl" +
            "wedducfurfexnulluclennerlexrupnedlecrydlydfenwelnydhusrelrudnesh" +
            "esfetdesretdunlernyrsebhulrylludremlysfynwerrycsugnysnyllyndynde" +
            "mluxfedsedbecmunlyrtesmudnytbyrsenwegfyrmurtelreptegpecnelnevfes" +
            ""
    ).chunked(3).toHashSet()
