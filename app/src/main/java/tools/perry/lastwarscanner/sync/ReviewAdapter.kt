package tools.perry.lastwarscanner.sync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tools.perry.lastwarscanner.R
import tools.perry.lastwarscanner.network.AliasMapping
import tools.perry.lastwarscanner.network.MemberSummary
import tools.perry.lastwarscanner.network.PreviewMatch
import tools.perry.lastwarscanner.network.PreviewResponse

/**
 * Drives the review RecyclerView with three view types:
 *  - Section header ("Matched" / "Needs Review")
 *  - Matched entry — read-only, green check
 *  - Unresolved entry — member picker button + alias save checkbox
 *
 * Call [setData] once the [PreviewResponse] is available.
 * Call [updateResolution] when the user picks a member in the picker dialog.
 * Call [getAliasChoices] when building the CommitRequest.
 */
class ReviewAdapter(
    private val onPickMember: (originalName: String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // ── Internal item model ───────────────────────────────────────────────────

    internal sealed class Item {
        data class Header(val title: String) : Item()
        data class Matched(val match: PreviewMatch) : Item()
        data class Unresolved(
            val match: PreviewMatch,
            var selectedMemberId: Int? = null,
            var selectedMemberName: String? = null,
            var saveAlias: Boolean = true
        ) : Item()
    }

    private val items = mutableListOf<Item>()

    // ── Public API ────────────────────────────────────────────────────────────

    fun setData(preview: PreviewResponse) {
        items.clear()
        if (preview.matched.isNotEmpty()) {
            items += Item.Header("Matched (${preview.matched.size})")
            preview.matched.forEach { items += Item.Matched(it) }
        }
        if (preview.unresolved.isNotEmpty()) {
            items += Item.Header("Needs Review (${preview.unresolved.size})")
            preview.unresolved.forEach { items += Item.Unresolved(it) }
        }
        notifyDataSetChanged()
    }

    /** Called from the host activity when the user selects a member in the picker dialog. */
    fun updateResolution(originalName: String, member: MemberSummary) {
        val idx = items.indexOfFirst { it is Item.Unresolved && it.match.originalName == originalName }
        if (idx < 0) return
        val item = items[idx] as Item.Unresolved
        item.selectedMemberId = member.id
        item.selectedMemberName = member.name
        notifyItemChanged(idx)
    }

    /**
     * Returns [AliasMapping] entries for all unresolved items where a member was
     * selected AND the alias checkbox is checked.
     */
    fun getAliasChoices(): List<AliasMapping> =
        items.filterIsInstance<Item.Unresolved>()
            .filter { it.selectedMemberId != null && it.saveAlias }
            .map {
                AliasMapping(
                    failedAlias = it.match.originalName,
                    memberId = it.selectedMemberId!!,
                    category = "ocr"
                )
            }

    // ── RecyclerView plumbing ─────────────────────────────────────────────────

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is Item.Header -> TYPE_HEADER
        is Item.Matched -> TYPE_MATCHED
        is Item.Unresolved -> TYPE_UNRESOLVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_review_header, parent, false))
            TYPE_MATCHED -> MatchedVH(inflater.inflate(R.layout.item_review_matched, parent, false))
            else -> UnresolvedVH(inflater.inflate(R.layout.item_review_unresolved, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as HeaderVH).bind(item)
            is Item.Matched -> (holder as MatchedVH).bind(item)
            is Item.Unresolved -> (holder as UnresolvedVH).bind(item)
        }
    }

    // ── ViewHolders ───────────────────────────────────────────────────────────

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvSectionTitle)
        internal fun bind(item: Item.Header) { tvTitle.text = item.title }
    }

    inner class MatchedVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvMatchedName)
        private val tvScore: TextView = view.findViewById(R.id.tvMatchedScore)
        private val tvMember: TextView = view.findViewById(R.id.tvMatchedMember)

        internal fun bind(item: Item.Matched) {
            tvName.text = item.match.originalName
            tvScore.text = item.match.score.toString()
            tvMember.text = item.match.matchedMember?.name ?: "—"
        }
    }

    inner class UnresolvedVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvUnresolvedName)
        private val tvScore: TextView = view.findViewById(R.id.tvUnresolvedScore)
        private val tvPickButton: TextView = view.findViewById(R.id.tvPickMember)
        private val cbSaveAlias: CheckBox = view.findViewById(R.id.cbSaveAlias)

        internal fun bind(item: Item.Unresolved) {
            tvName.text = item.match.originalName
            tvScore.text = item.match.score.toString()

            if (item.selectedMemberId != null) {
                tvPickButton.text = item.selectedMemberName
                cbSaveAlias.visibility = View.VISIBLE
                cbSaveAlias.isChecked = item.saveAlias
            } else {
                tvPickButton.text = itemView.context.getString(R.string.review_select_member)
                cbSaveAlias.visibility = View.GONE
            }

            tvPickButton.setOnClickListener {
                onPickMember(item.match.originalName)
            }

            cbSaveAlias.setOnCheckedChangeListener { _, checked ->
                item.saveAlias = checked
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_MATCHED = 1
        private const val TYPE_UNRESOLVED = 2
    }
}
