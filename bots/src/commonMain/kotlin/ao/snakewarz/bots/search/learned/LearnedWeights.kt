package ao.snakewarz.bots.search.learned

/**
 * The fitted model, as the one thing about a learned evaluation that is data rather than code.
 *
 * Written by `:lab`'s `train`, which prints this file's body at the end of a run so that adopting a
 * fit is a paste rather than a transcription. Everything about the shape — how many features, how
 * many hidden units, which weight sits where — is inside the literal, so [LearnedNet.decode] refuses
 * a model that does not match the [PositionFeatures] compiled beside it instead of silently reading
 * the wrong column.
 *
 * A **string** rather than a `doubleArrayOf`, and that is the whole reason this is a separate file:
 * a large array literal compiles to code in Kotlin/Wasm where a string literal becomes a data segment
 * that gzips. SW-08 in `docs/Coding-Standards.md` is the budget it is spent against.
 *
 * Replacing this is the same kind of act as moving a knob's default: it changes how `eval=learned`
 * plays every game, so it carries the measurement that moved it, exactly as `docs/Bots.md`'s
 * adoption sequence asks.
 *
 * ### Where this one came from
 *
 * `train --log .lab/p2b-field-8,.lab/p2b-field-12,.lab/p2b-field-20 --positions 1000000
 * --seed 20260729` — 965,878 rows off **39,600 logged matches at three board sizes**, 13,200 each at
 * 8x8, 12x12 and 20x20, replayed out of P2's three equal-clock fields. Everything else is `train`'s
 * default and therefore the setting the previous fit used: stride 14, sixteen hidden units, decay
 * `1e-5`, sixty epochs.
 *
 * | | |
 * |---|---|
 * | holdout log-loss | **0.57370**, against 0.69315 for a model that answers even |
 * | holdout accuracy | 68.5% |
 * | training log-loss | 0.57278 — a gap of 0.0009, so nothing here is memorised |
 * | spread of its answers | 0.228 either side of even |
 *
 * ### What moved it, and it was not the features
 *
 * The fit this replaces was taken `--rows 12 --cols 12`, and neither this KDoc nor [LearnedEval]'s
 * said the fit was only known at that size. P2 found the leaf rating **−167** at equal clock on a
 * 20x20 against the bare baseline's +149. Scored directly, on 13,200 fresh matches per board that no
 * fit here has seen:
 *
 * | | 8x8 | 12x12 | 20x20 |
 * |---|---|---|---|
 * | the old 12x12 fit, log-loss | 0.5475 | 0.5822 | **0.6274** |
 * | a fit of the same 25 readings on *that* board | 0.5364 | 0.5685 | **0.5798** |
 * | this fit, held out | 0.5323 | 0.5721 | **0.5857** |
 *
 * **A 20x20 board is not harder to call — the old fit could not call it.** Refitting the identical
 * twenty-five readings on 20x20 positions is worth **0.048 of log-loss and 3.6 points of accuracy**
 * there, where on the two smaller boards the same refit is worth 0.011 and 0.014. So the ceiling the
 * old fit ran into was its **corpus** and not its feature count, and the four readings P4 added off
 * the residual are worth **0.0039 ± 0.0017** pooled over three seeds — a tenth of what the corpus was
 * worth, and a sixth of what the hidden layer was worth. [PositionFeatures] carries which four.
 *
 * The training and holdout columns landing on the same number was read as *"bounded by what
 * twenty-five readings can say"*, and that inference does not hold: a holdout drawn from a one-board
 * corpus is a statement about capacity on that board and cannot see a transfer failure at all. The
 * equality is still true here at three boards and 966,000 rows, and still means only what it says.
 *
 * ### And it shows up at the board, on the one board it was supposed to
 *
 * 4,200 matches per board against the same field, rated against the bare baseline. At **equal
 * allowance** this fit rates +118 / +252 / **+88** where the one it replaces rated +92 / +81 /
 * **−160**; at equal clock, +82 / +4 / **−74** against +40 / −7 / **−316**. The 20x20 move of roughly
 * +240 is the finding — the two smaller boards moved by less than composition alone shifts a contrast
 * between two fields, and are not evidence of anything. **The prediction and the outcome agree about
 * which board**: 0.048 of the loss was corpus at 20x20 against 0.011 and 0.014 at the other two.
 *
 * ### What is still not measured
 *
 * The corpus is P2's field, which seats `chase` as its connectivity anchor, so about a sixth of the
 * matches in it are a searcher against a reactive bot rather than the searcher-against-searcher play
 * the previous corpus was filtered to. It is the same sixth on all three boards, so it cannot bias
 * the board comparison above, and it is not known what it costs.
 *
 * **The two fits have never played each other.** Everything above compares two fields, and seating
 * both literals in one would need two of them compiled in at once. The loss table is the direct
 * reading; the Elo is not.
 */
internal object LearnedWeights {
    /**
     * The shipped fit — see [LearnedEval] for what it is worth and what it was fitted on.
     *
     * `<version>|<features>|<hidden units>|<weight>,...`, each weight a fixed-point integer over
     * [LearnedNet.WEIGHT_SCALE].
     */
    const val ENCODED: String =
        "1|29|16|14995,14422,-61884,30959,19361,-9932,-76370,-51142,-9588,-30290,-33277,-569,-2699,-36370,-10" +
            "66,-97628,-139932,132320,-46714,165863,-85724,33663,-191078,45255,-29691,-1249,30796,44335,14156,-62" +
            "012,-62012,-80215,11393,8270,19760,-7604,18420,39688,6041,-2462,-56439,-7336,18164,-6418,-53218,9934" +
            "7,-9962,-27277,95207,-11213,2148,32893,-36720,-14735,21018,-11528,-48432,152170,8949,8944,1873,42096" +
            ",-2661,-6022,-60646,56036,-20436,52283,-32049,347,-37373,110632,2861,-128428,115405,34804,-56695,-61" +
            "830,-2486,-161626,29653,39450,-22720,7769,64562,-24614,-33606,-21985,-22335,-93966,57141,74724,-6100" +
            "0,-17603,37639,-8049,18394,-6678,105139,-61992,15278,1810,-45967,-144223,26252,-97800,51877,-8716,-1" +
            "81680,62835,-28937,12319,21084,32483,-813,-93132,17358,17433,16923,9895,148439,6510,-30239,-66981,-9" +
            "0771,21614,-88016,40517,10966,53091,-12047,79208,-835,-37522,127274,-78640,-44487,64754,6983,-3102,2" +
            "0609,31003,36295,41896,106175,-617,-617,-560,-24,-290,152,93,198,-1062,-101,468,-241,-5,221,1238,-24" +
            "8,79,-873,-146,114,152,-161,16,-142,-412,116,100,136,-23,-35625,-35556,-46300,-41208,-15195,-58680,1" +
            "5736,25105,-36899,40549,107018,-27700,-8505,34651,-664,174,-142034,-41604,-95796,-68277,56645,33501," +
            "10151,-73953,53207,42188,5878,-11952,-28029,-874,-874,-746,-210,-311,320,-99,498,-827,102,298,232,73" +
            ",484,1750,-102,272,-112,390,15,125,-369,208,-291,-609,352,-362,-21,968,-3630,-3341,-74060,-3498,4519" +
            "7,-108792,-24353,14205,-14529,-24758,18803,32460,-30962,-72981,-842,-61712,-131991,62302,39315,52089" +
            ",-80328,63601,-210181,9278,111,29776,10898,23385,-79186,92950,93150,113331,-49955,36486,-53303,-2846" +
            "9,4568,27381,-21624,54691,-59662,-51829,9199,-2390,3349,20946,-56903,36927,41204,-62111,-28371,73339" +
            ",108865,-3181,-47883,-26020,-27183,-74559,23945,23944,19645,6711,28217,-34636,11761,47979,66340,-818" +
            "27,-20705,28612,-35905,28194,6552,7306,-80699,92671,-10386,-6786,-19440,-12857,72025,-50523,78020,10" +
            "365,-55759,-83580,-27712,-32440,-32494,-51362,29896,10441,11211,21799,-5965,51711,-24070,86991,-1767" +
            "9,22580,-49431,10598,-100150,-106553,12704,14325,-98028,22284,-154601,56237,-13805,-20313,-11958,939" +
            ",-46964,-6681,-15691,-15609,-50660,27077,110296,-146714,-51269,114234,-63174,41558,22849,12927,-2256" +
            "4,25628,-620,-5984,-108665,41082,12304,183191,-7790,82427,-111576,43402,-60645,-26105,52107,55995,-3" +
            "7324,14632,14447,-56080,4300,78949,54539,49529,74053,-2830,142755,30037,23538,-4009,-16593,-2644,155" +
            "068,-163940,-41128,41235,29696,70232,-92915,272683,70475,-73874,-10343,34994,-54249,-80204,6923,6749" +
            ",-34978,11366,-5379,-24038,6854,-6787,28389,-58392,-5055,12496,-19977,37334,1201,68525,-154214,-1018" +
            "15,-7848,-17437,26637,132985,15917,-69661,82145,-24965,-3787,-1664,-5166,633,633,575,639,321,-165,-4" +
            "7,-134,967,149,-338,215,117,-214,-1225,430,-74,844,89,-26,-178,340,-10,163,393,16,-146,-118,75,-6209" +
            "1,-4221,243725,87983,-97138,-616,48272,-757,-55260,-375665,67822,137031,12793,-8553,-63020,-189,-106" +
            "109,-38764,127846,-93406,-126459,-209,-49593,11,-137928,155014,36409,-113260,111979,-204484,-81818,2" +
            "04,34834"
}
