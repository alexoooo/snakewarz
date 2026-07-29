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
 * `train --log <the twelve strong-play batches> --rows 12 --cols 12 --stride 14 --positions 600000
 * --hidden 16 --decay 1e-5 --epochs 60 --seed 20260729` — 600,000 positions off 22,452 logged
 * matches, every one of them a searcher against a searcher, replayed out of P1-P6's `ab` logs.
 *
 * | | |
 * |---|---|
 * | holdout log-loss | **0.568**, against 0.693 for a model that answers even |
 * | holdout accuracy | 67.8% |
 * | training log-loss | 0.568 — the same, so nothing here is memorised |
 * | spread of its answers | 0.230 either side of even |
 *
 * The training and holdout columns landing on the same number is the finding rather than a
 * reassurance: at 433 weights against half a million positions the fit is limited by **what the
 * twenty-five readings can say**, not by capacity or by data. [LearnedEval] carries what it is worth
 * at the board.
 */
internal object LearnedWeights {
    /**
     * The shipped fit — see [LearnedEval] for what it is worth and what it was fitted on.
     *
     * `<version>|<features>|<hidden units>|<weight>,...`, each weight a fixed-point integer over
     * [LearnedNet.WEIGHT_SCALE].
     */
    const val ENCODED: String =
        "1|25|16|-741,-741,-699,-135,-360,427,-288,407,149,414,-89,-571,-789,162,2023,-265,-1915,935,672,144," +
            "42,-584,-78,-263,-803,104,104,409,304,-94,-157,314,-210,-465,-174,-741,265,135,-19,-511,443,522,-503" +
            ",287,-63,196,325,81,-13,304,-25795,-22676,-47201,-12763,16708,21211,-51086,-48097,-37890,-5602,-1716" +
            ",15218,-45767,83920,-4675,-44795,-80635,-86989,-187,-32284,16069,345001,21895,-40353,22427,5186,4979" +
            ",-38537,44214,21641,-6029,-82359,-21481,50865,94453,16558,-9148,22559,-20514,1468,-48348,-31899,1475" +
            "72,-71376,-96069,-46389,-158937,-11748,-61021,73692,21737,22335,1991,-77945,49310,50971,-94858,-2738" +
            "4,9813,-158365,-1842,-103654,-20229,-7290,-4889,-121851,209944,-50864,9774,32235,-3855,382579,39127," +
            "34616,-4186,-6221,-6529,21538,-127292,44692,-19849,122447,-20935,138199,31570,9989,30201,-3998,-7885" +
            "7,-7316,67988,-132901,2860,92069,-29602,-96104,74055,2500,-14642,10300,29829,26023,-66572,32771,-918" +
            "01,-44797,-26520,4599,5275,5635,-40721,50699,2222,-11469,-1743,96381,-249321,20061,-169814,201875,-1" +
            "00219,38466,-4977,38767,-3365,4483,4049,27946,27801,61852,5825,22627,-107359,-441,59138,10311,-58642" +
            ",22738,-88734,-3183,-14294,-81890,-22081,167221,80497,36452,-116279,-13253,-35602,38291,49324,49341," +
            "82908,-38300,75351,-19257,6938,-38796,9659,126501,-60813,28076,64943,22125,22045,-16160,-193263,3699" +
            "9,45771,46955,39401,-49112,3424,12786,30300,-35453,-32311,-4973,-59685,16191,27953,-71092,17560,7249" +
            "5,-5279,-7097,16603,22888,-43240,2099,100003,163365,28900,-67612,64791,-31915,141708,9783,-64351,263" +
            "60,40303,39630,-45521,-5896,100909,94766,25544,78333,-52548,54282,15381,27518,60485,3084,-3263,28615" +
            ",-162927,-10463,99738,-55765,115652,79939,-10169,67558,-38772,25023,26330,-112452,-10469,80800,-5842" +
            "7,-132492,16176,13762,-101915,-20489,29706,5602,-95534,-1137,122809,-221369,45950,23421,100861,-1269" +
            "81,85511,3786,79617,-44449,-13077,-11184,-56809,-87105,5606,-11974,-86027,-14867,191756,-147499,1640" +
            "5,-68113,-21483,23496,-60,-115061,-257674,-32378,-104045,67278,39168,238656,13294,22163,-24498,-2461" +
            "2,-27696,-47128,46953,127180,61412,19886,-6607,-52213,3760,35297,-18791,14341,-52801,7455,-72521,-14" +
            "2681,-31198,18993,-24661,606,-151607,-3826,-55489,26879,-8533,-7172,-10656,10507,41796,-54856,3083,-" +
            "66747,20583,-71602,-7266,-4617,-75164,1275,4258,38706,68475,-21188,19446,-59627,133668,50017,436,-13" +
            "2795,116338,-242,-242,-477,-117,47,216,-457,280,502,242,903,-375,-216,27,748,-369,-832,674,-130,181," +
            "-16,-373,-128,10,-506,-845,524,-111924,20619,-119662,-95104,-31310,-7086,-41755,-87162,-54365,-13167" +
            "5,-185407,95220,-80506,-1240,464,-74,-145236,-73859,223894,-106921,-100459,-83967,50227,104612,-1383" +
            "25,-140716,-166019,-103427,-55519,165,-14496"
}
