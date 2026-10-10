package io.github.chronosauros.contour.core.native

/** Descriptor sizes include exactly one ID. Input/Output/Feature are independent namespaces. */
object NativeHidReports {
    enum class Kind(val tag: Int, val controlType: Int) { INPUT(0x80, 1), OUTPUT(0x90, 2), FEATURE(0xB0, 3) }
    data class Key(val id: Int, val kind: Kind)
    data class Shape(val rawSizes: Map<Key, Int>, val owners: Map<Key, Int>) {
        fun rawSize(id: Int, kind: Kind): Int = rawSizes[Key(id, kind)] ?: error("Missing $kind report $id")
        fun payloadSize(id: Int, kind: Kind): Int = rawSize(id, kind) - 1
        fun qualifyMoondrop(model: MoondropCatalog.Model) {
            val frames = MoondropCodec.readQueries(model)
            require(payloadSize(model.reportId, Kind.OUTPUT) >= frames.maxOf { it.minimumDescriptorOutputPayloadBytes }) { "Incomplete Moondrop Output descriptor" }
            require(payloadSize(model.reportId, Kind.INPUT) >= 36) { "Moondrop Input lacks complete coefficient/metadata fields" }
            requireSameOwner(model.reportId, Kind.INPUT, Kind.OUTPUT)
        }
        fun requireSameOwner(id: Int, a: Kind, b: Kind) {
            require(owners.getValue(Key(id, a)) == owners.getValue(Key(id, b))) { "Report kinds belong to different collections" }
        }
        fun wire(id: Int, kind: Kind, payload: ByteArray): ByteArray {
            val n = rawSize(id, kind)
            require(id in 1..255 && payload.size <= n - 1) { "Payload exceeds descriptor" }
            return ByteArray(n).also { it[0] = id.toByte(); payload.copyInto(it, 1) }
        }
        fun payload(id: Int, kind: Kind, raw: ByteArray): ByteArray {
            require(raw.size == rawSize(id, kind) && (raw[0].toInt() and 255) == id) { "Raw report ID/length mismatch" }
            return raw.copyOfRange(1, raw.size)
        }
    }
    /** Raw scan result: bits per (ID, kind), owning collection and whether every item sits on a vendor usage page. */
    private class Scan(val bits: Map<Key, Int>, val owners: Map<Key, Int>, val vendors: Map<Key, Boolean>)

    /** [undefinedDesktop] (FiiO only): a top-level Application collection on Generic Desktop with the one Usage 0x00
     * (Undefined) counts as a vendor page - the FiiO K13 R2R declares report 7 that way (real descriptor, 10.10.2026). */
    fun parse(descriptor: ByteArray, undefinedDesktop: Boolean = false): Shape {
        val scan = scan(descriptor, undefinedDesktop)
        val owners = scan.owners
        val sizes = scan.bits.filterKeys { it.id != 0 }.filterKeys { scan.vendors[it] == true }.mapValues { (_, b) ->
            require(b % 8 == 0 && b / 8 in 1..1023) { "Unsupported report size" }; 1 + b / 8
        }
        return Shape(sizes, owners.filterKeys { it in sizes })
    }

    /** One declared report as the descriptor states it (diagnostics only; [bytes] counts the report ID, -1 = not whole bytes). */
    data class Declared(val id: Int, val kind: Kind, val bytes: Int, val owner: Int, val vendor: Boolean)

    /** Every report ID the descriptor declares, vendor page or not; never used to authorise I/O. */
    fun inventory(descriptor: ByteArray, undefinedDesktop: Boolean = false): List<Declared> {
        val scan = scan(descriptor, undefinedDesktop)
        return scan.bits.filterKeys { it.id != 0 }.map { (k, b) ->
            Declared(k.id, k.kind, if (b % 8 == 0) 1 + b / 8 else -1, scan.owners.getValue(k), scan.vendors[k] == true)
        }.sortedWith(compareBy({ it.id }, { it.kind.ordinal }))
    }

    private fun scan(descriptor: ByteArray, undefinedDesktop: Boolean): Scan {
        data class Global(val size: Int = 0, val count: Int = 0, val id: Int = 0, val page: Int = 0)
        var g = Global(); val stack = ArrayList<Global>(); val vendorStack = ArrayList<Boolean>()
        val ownerStack = ArrayList<Int>(); var nextOwner = 0
        var usages = 0; var usage = -1 // local Usage items since the last main item
        val bits = HashMap<Key, Int>(); val owners = HashMap<Key, Int>(); val vendors = HashMap<Key, Boolean>()
        var i = 0
        while (i < descriptor.size) {
            val p = descriptor[i++].toInt() and 255
            require(p != 0xFE) { "Unsupported long descriptor item" }
            val n = if (p and 3 == 3) 4 else p and 3
            require(i + n <= descriptor.size) { "Truncated descriptor" }
            var value = 0; repeat(n) { value = value or ((descriptor[i++].toInt() and 255) shl (it * 8)) }
            when (p and 0xFC) {
                0x04 -> g = g.copy(page = value)
                0x74 -> g = g.copy(size = value)
                0x94 -> g = g.copy(count = value)
                0x84 -> { require(value in 1..255); g = g.copy(id = value) }
                0xA4 -> stack.add(g)
                0xB4 -> { require(stack.isNotEmpty()); g = stack.removeAt(stack.lastIndex) }
                0x08 -> { usages++; usage = if (n <= 2) value else -1 }
                0xA0 -> { ownerStack.add(ownerStack.firstOrNull() ?: ++nextOwner); vendorStack.add(g.page in 0xFF00..0xFFFF || vendorStack.lastOrNull() == true ||
                    undefinedDesktop && vendorStack.isEmpty() && value == 1 && g.page == 0x01 && usages == 1 && usage == 0) }
                0xC0 -> { require(ownerStack.isNotEmpty()); ownerStack.removeAt(ownerStack.lastIndex); vendorStack.removeAt(vendorStack.lastIndex) }
                0x80, 0x90, 0xB0 -> {
                    require(g.size in 1..32 && g.count in 1..8192 && ownerStack.isNotEmpty())
                    val key = Key(g.id, Kind.entries.single { it.tag == (p and 0xFC) })
                    val owner = ownerStack.first()
                    require(key !in owners || owners[key] == owner) { "Ambiguous report collection" }
                    owners[key] = owner
                    bits[key] = (bits[key] ?: 0) + g.size * g.count
                    vendors[key] = (vendors[key] ?: true) && (vendorStack.last() || g.page in 0xFF00..0xFFFF)
                }
            }
            if ((p and 0xFC) in setOf(0x80, 0x90, 0xB0, 0xA0, 0xC0)) { usages = 0; usage = -1 } // a main item ends the locals
        }
        require(stack.isEmpty() && ownerStack.isEmpty()) { "Unclosed descriptor state" }
        return Scan(bits, owners, vendors)
    }
}
