package tools.perry.lastwarscanner

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import tools.perry.lastwarscanner.model.MemberRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScoreAdapter : ListAdapter<MemberRow, ScoreAdapter.MemberViewHolder>(MemberDiffCallback()) {

    private val expandedNames = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_member_card, parent, false)
        return MemberViewHolder(view)
    }

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        holder.bind(getItem(position), getItem(position).name in expandedNames) {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_ID.toInt()) return@bind
            if (it in expandedNames) expandedNames.remove(it) else expandedNames.add(it)
            notifyItemChanged(pos)
        }
    }

    inner class MemberViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvName)
        private val tvMeta: TextView = view.findViewById(R.id.tvMeta)
        private val ivChevron: ImageView = view.findViewById(R.id.ivChevron)
        private val llCardHeader: View = view.findViewById(R.id.llCardHeader)
        private val divider: View = view.findViewById(R.id.dividerScores)
        private val chipGroup: ChipGroup = view.findViewById(R.id.chipGroupScores)

        fun bind(item: MemberRow, expanded: Boolean, onToggle: (String) -> Unit) {
            tvName.text = item.name

            val scoreCount = item.scores.count { it.value > 0 }
            val timeStr = if (item.latestTimestamp > 0) relativeTime(item.latestTimestamp) else "—"
            tvMeta.text = "$scoreCount score${if (scoreCount != 1) "s" else ""} • $timeStr"

            ivChevron.rotation = if (expanded) 180f else 0f
            divider.visibility = if (expanded) View.VISIBLE else View.GONE
            chipGroup.visibility = if (expanded) View.VISIBLE else View.GONE

            if (expanded) {
                chipGroup.removeAllViews()
                item.scores
                    .filter { it.value > 0 }
                    .entries
                    .sortedBy { categoryOrder(it.key) }
                    .forEach { (key, score) ->
                        val chip = Chip(itemView.context).apply {
                            text = "${keyLabel(key)}: ${abbreviate(score)}"
                            isClickable = false
                            isFocusable = false
                            chipMinHeight = 28f * resources.displayMetrics.density
                            textSize = 11f
                            chipBackgroundColor = ColorStateList.valueOf(
                                ContextCompat.getColor(itemView.context, chipColor(key))
                            )
                            setTextColor(android.graphics.Color.BLACK)
                            chipStrokeColor = ColorStateList.valueOf(
                                ContextCompat.getColor(itemView.context, chipBorderColor(key))
                            )
                            chipStrokeWidth = 1f
                        }
                        chipGroup.addView(chip)
                    }
            }

            llCardHeader.setOnClickListener {
                ivChevron.animate().rotation(if (item.name in expandedNames) 0f else 180f)
                    .setDuration(180).start()
                onToggle(item.name)
            }
        }
    }

    class MemberDiffCallback : DiffUtil.ItemCallback<MemberRow>() {
        override fun areItemsTheSame(old: MemberRow, new: MemberRow) = old.name == new.name
        override fun areContentsTheSame(old: MemberRow, new: MemberRow) = old == new
    }

    companion object {
        private val KEY_LABELS = mapOf(
            // New-style tab IDs
            "monday" to "Mon", "tuesday" to "Tue", "wednesday" to "Wed",
            "thursday" to "Thu", "friday" to "Fri", "saturday" to "Sat",
            "weekly" to "Weekly",
            "power" to "Power", "kills" to "Kills",
            "donation_daily" to "Donation (D)", "donation_weekly" to "Donation (W)",
            "mutual_assistance_daily" to "MA Daily", "mutual_assistance_weekly" to "MA Weekly",
            "mutual_assistance_season" to "MA Season",
            "siege_daily" to "Siege Daily", "siege_weekly" to "Siege Weekly",
            "siege_season" to "Siege Season",
            "rare_soil_war_daily" to "RSW Daily", "rare_soil_war_weekly" to "RSW Weekly",
            "rare_soil_war_season" to "RSW Season",
            "defeat_daily" to "Defeat Daily", "defeat_weekly" to "Defeat Weekly",
            "defeat_season" to "Defeat Season",
            // Legacy keys (kept for existing DB data)
            "Mon" to "Mon", "Tues" to "Tue", "Wed" to "Wed", "Thur" to "Thu",
            "Fri" to "Fri", "Sat" to "Sat",
            "Power" to "Power", "Kills" to "Kills", "Donation" to "Donation",
            "Weekly" to "Weekly",
        )

        private val CATEGORY_ORDER = listOf(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
            "Mon", "Tues", "Wed", "Thur", "Fri", "Sat",
            "weekly", "Weekly",
            "power", "kills", "donation_daily", "donation_weekly",
            "Power", "Kills", "Donation",
            "mutual_assistance_daily", "mutual_assistance_weekly", "mutual_assistance_season",
            "siege_daily", "siege_weekly", "siege_season",
            "rare_soil_war_daily", "rare_soil_war_weekly", "rare_soil_war_season",
            "defeat_daily", "defeat_weekly", "defeat_season",
        )

        fun keyLabel(key: String): String =
            KEY_LABELS[key] ?: key.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

        fun categoryOrder(key: String): Int =
            CATEGORY_ORDER.indexOf(key).takeIf { it >= 0 } ?: Int.MAX_VALUE

        fun chipColor(key: String): Int = when {
            key.lowercase().let {
                it == "monday" || it == "tuesday" || it == "wednesday" ||
                it == "thursday" || it == "friday" || it == "saturday" ||
                it == "mon" || it == "tues" || it == "wed" || it == "thur" || it == "fri" || it == "sat"
            } -> R.color.chip_daily
            key.equals("weekly", ignoreCase = true) -> R.color.chip_weekly
            key.lowercase().let { it.startsWith("power") || it.startsWith("kills") || it.startsWith("donation") } -> R.color.chip_strength
            key.lowercase().let {
                it.startsWith("mutual") || it.startsWith("siege") ||
                it.startsWith("rare") || it.startsWith("defeat")
            } -> R.color.chip_alliance
            key.equals("Unknown", ignoreCase = true) -> R.color.chip_unknown
            else -> R.color.chip_other
        }

        fun chipBorderColor(key: String): Int = when {
            key.equals("Unknown", ignoreCase = true) -> R.color.chip_unknown_border
            else -> R.color.chip_border
        }

        fun abbreviate(score: Long): String = when {
            score >= 1_000_000_000L -> String.format(Locale.US, "%.1fB", score / 1_000_000_000.0)
            score >= 1_000_000L     -> String.format(Locale.US, "%.1fM", score / 1_000_000.0)
            score >= 1_000L         -> String.format(Locale.US, "%.1fK", score / 1_000.0)
            else                    -> score.toString()
        }

        private fun relativeTime(ts: Long): String {
            val diff = System.currentTimeMillis() - ts
            return when {
                diff < 60_000L      -> "just now"
                diff < 3_600_000L   -> "${diff / 60_000L}m ago"
                diff < 86_400_000L  -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
                else                -> "${diff / 86_400_000L}d ago"
            }
        }
    }
}
