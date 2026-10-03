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
    fun parse(descriptor: ByteArray): Shape {
        data class Global(val size: Int = 0, val count: Int = 0, val id: Int = 0, val page: Int = 0)
        var g = Global(); val stack = ArrayList<Global>(); val vendorStack = ArrayList<Boolean>()
        val ownerStack = ArrayList<Int>(); var nextOwner = 0
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
                0xA0 -> { ownerStack.add(ownerStack.firstOrNull() ?: ++nextOwner); vendorStack.add(g.page in 0xFF00..0xFFFF || vendorStack.lastOrNull() == true) }
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
        }
        require(stack.isEmpty() && ownerStack.isEmpty()) { "Unclosed descriptor state" }
        val sizes = bits.filterKeys { it.id != 0 }.filterKeys { vendors[it] == true }.mapValues { (_, b) ->
            require(b % 8 == 0 && b / 8 in 1..1023) { "Unsupported report size" }; 1 + b / 8
        }
        return Shape(sizes, owners.filterKeys { it in sizes })
    }
}
