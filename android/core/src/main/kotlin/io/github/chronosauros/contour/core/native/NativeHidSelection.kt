package io.github.chronosauros.contour.core.native

/**
 * Which HID interface of a FiiO DAC carries the vendor report, decided from report descriptors and endpoint
 * descriptors alone (no USB calls, so it is unit-tested). The USB side only reads descriptors and applies the choice.
 *
 * Why FiiO has its own rule: a desktop DAC can expose several HID interfaces (consumer-control knob next to the
 * vendor PEQ interface), and FiiO's own web app picks "the device with a collection that has report ID 7 as input AND
 * output" (WebHID writes with sendReport(7, ...) and reads inputreport events; it does not care how the endpoints are
 * sized). Moondrop, Fosi, KT and WalkPlay keep their own, stricter, selection in HidTransport.
 */
object NativeHidSelection {
    /** An interrupt endpoint of a HID interface. */
    data class Endpoint(val address: Int, val input: Boolean, val maxPacket: Int) {
        override fun toString() = "${if (input) "IN" else "OUT"} 0x${Integer.toHexString(address)} mps $maxPacket"
    }

    /** What the USB side learned about one HID interface: its report descriptor, or why that failed. */
    class Candidate(val interfaceId: Int, val descriptor: ByteArray?, val failure: String?, val endpoints: List<Endpoint>)

    data class Verdict(val interfaceId: Int, val accepted: Boolean, val reason: String)

    class Choice(
        val interfaceId: Int,
        val shape: NativeHidReports.Shape,
        val input: Endpoint,
        /** Interrupt OUT to write to; null = Output report through SET_REPORT on the control pipe. */
        val output: Endpoint?,
        /** True when the strict endpoint rule (one interrupt IN big enough for the whole report) did not hold. */
        val relaxed: Boolean,
        val note: String,
    )

    class Result(val choice: Choice?, val verdicts: List<Verdict>) {
        /** The reasons of every interface, short enough for a screenshot of the EQ page. */
        val reasons: String get() = if (verdicts.isEmpty()) "no HID interface" else verdicts.joinToString("; ") { "if ${it.interfaceId}: ${it.reason}" }
        val failure: String? get() = if (choice != null) null else "No unambiguous descriptor-proven family HID interface ($reasons)"
    }

    private sealed interface Assessment {
        class Rejected(val reason: String) : Assessment
        class Qualified(val shape: NativeHidReports.Shape, val input: Endpoint, val output: Endpoint?, val strict: Boolean, val note: String) : Assessment
    }

    /**
     * [reportId] is the FiiO report (7), [minimum] the raw report size the codec's largest frame needs (17 B).
     * Qualification, per HID interface with a readable descriptor: report [reportId] declared on a vendor usage page (or
     * in a top-level Generic Desktop collection with Usage Undefined, as on the K13 R2R) as
     * Input and Output of at least [minimum] bytes in the same collection, plus an interrupt IN endpoint. Preference:
     * interfaces where one interrupt IN is large enough for the whole report (the old strict rule) before the ones
     * that only have a smaller or an additional interrupt IN (WebHID-equivalent: the first interrupt IN is read); then an
     * interrupt OUT before SET_REPORT; then the lowest interface number. Sizes are never relaxed: a descriptor that
     * declares a report smaller than the codec's frames stays rejected.
     */
    fun selectFiio(reportId: Int, minimum: Int, candidates: List<Candidate>): Result {
        val verdicts = ArrayList<Verdict>()
        val qualified = ArrayList<Pair<Candidate, Assessment.Qualified>>()
        for (c in candidates) {
            when (val a = assess(reportId, minimum, c)) {
                is Assessment.Rejected -> verdicts += Verdict(c.interfaceId, false, a.reason)
                is Assessment.Qualified -> qualified += c to a
            }
        }
        if (qualified.isEmpty()) return Result(null, verdicts)
        val ranked = qualified.sortedWith(compareBy({ if (it.second.strict) 0 else 1 }, { if (it.second.output != null) 0 else 1 }, { it.first.interfaceId }))
        val (best, a) = ranked.first()
        val note = buildString {
            append(a.note)
            if (ranked.size > 1) append("; ${ranked.size} interfaces qualified (${ranked.joinToString { it.first.interfaceId.toString() }}), chose if ${best.interfaceId}")
        }
        for ((c, q) in ranked) verdicts += Verdict(c.interfaceId, c === best, if (c === best) "OK ${q.note}" else "qualified, not chosen")
        verdicts.sortBy { it.interfaceId }
        return Result(Choice(best.interfaceId, a.shape, a.input, a.output, !a.strict, note), verdicts)
    }

    private fun assess(id: Int, minimum: Int, c: Candidate): Assessment {
        c.failure?.let { return Assessment.Rejected(it) }
        val descriptor = c.descriptor ?: return Assessment.Rejected("no report descriptor")
        val shape = try { NativeHidReports.parse(descriptor, undefinedDesktop = true) } catch (e: Exception) {
            return Assessment.Rejected(declares(descriptor, "descriptor: ${e.message ?: e.javaClass.simpleName}"))
        }
        val declared = NativeHidReports.inventory(descriptor, undefinedDesktop = true).filter { it.id == id }
        if (declared.isEmpty()) return Assessment.Rejected(declares(descriptor, "no report $id"))
        fun absent(kind: NativeHidReports.Kind, label: String): String {
            val d = declared.firstOrNull { it.kind == kind } ?: return "report $id has no $label"
            return if (!d.vendor) "report $id $label not on a vendor usage page" else "report $id $label unusable"
        }
        val out = shape.rawSizes[NativeHidReports.Key(id, NativeHidReports.Kind.OUTPUT)]
            ?: return Assessment.Rejected(absent(NativeHidReports.Kind.OUTPUT, "output"))
        val inp = shape.rawSizes[NativeHidReports.Key(id, NativeHidReports.Kind.INPUT)]
            ?: return Assessment.Rejected(absent(NativeHidReports.Kind.INPUT, "input"))
        if (out < minimum) return Assessment.Rejected("report $id output $out B < $minimum")
        if (inp < minimum) return Assessment.Rejected("report $id input $inp B < $minimum")
        if (shape.owners.getValue(NativeHidReports.Key(id, NativeHidReports.Kind.INPUT)) !=
            shape.owners.getValue(NativeHidReports.Key(id, NativeHidReports.Kind.OUTPUT)))
            return Assessment.Rejected("report $id input and output in different collections")
        val ins = c.endpoints.filter { it.input }
        val outs = c.endpoints.filter { !it.input }
        if (ins.isEmpty()) return Assessment.Rejected("no interrupt IN endpoint")
        val sized = ins.filter { it.maxPacket >= inp }
        val strict = sized.size == 1
        val input = if (strict) sized.single() else ins.first()
        val output = outs.firstOrNull { it.maxPacket >= out }
        val how = buildString {
            append("report $id in $inp B/out $out B, $input")
            append(if (output != null) ", $output" else ", SET_REPORT out")
            if (!strict) append(if (sized.isEmpty()) "; IN smaller than the report, first IN used" else "; ${ins.size} interrupt IN, first used")
        }
        return Assessment.Qualified(shape, input, output, strict, how)
    }

    /** The reason plus what the descriptor does declare, so one screenshot tells what the interface is. */
    private fun declares(descriptor: ByteArray, reason: String): String {
        val list = runCatching { NativeHidReports.inventory(descriptor, undefinedDesktop = true) }.getOrNull() ?: return reason
        if (list.isEmpty()) return "$reason (no numbered reports)"
        val items = list.take(6).joinToString(" ") { "${it.id}:${if (it.kind == NativeHidReports.Kind.INPUT) "in" else if (it.kind == NativeHidReports.Kind.OUTPUT) "out" else "feat"}${if (it.bytes < 0) "?" else it.bytes}${if (it.vendor) "" else "*"}" }
        return "$reason (declares $items${if (list.size > 6) " ..." else ""}; * = not vendor page)"
    }
}
