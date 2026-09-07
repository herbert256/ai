package com.ai.ui.report.view

import android.content.Context
import com.ai.ui.helpers.RerankRow
import com.ai.ui.shared.shareExportText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------
// Value view → single self-contained HTML page (inline CSS + JS, no
// external deps), shared via the system share sheet. One tab per ranking
// source (the same set as the screen's chips); each tab renders that
// ranking's cost × quality scatter as an SVG plus the ranked model list,
// mirroring the screen. Clicking a graph opens it full screen (a CSS
// overlay — the Fullscreen API is unreliable in mobile browsers).
// The per-source rows/points come from the SAME functions the screen
// uses (rowsForSource / buildValuePoints), so export always matches
// what's on screen — including the user's Combined ranking weights.
// ---------------------------------------------------------------------

/** One rendered tab: a ranking source that produced at least one point. */
private data class ExportTab(val key: String, val label: String, val points: List<ValuePoint>)

/** Build the page. [defaultSourceKey] (the screen's current selection)
 *  becomes the initially active tab when it survived the empty-tab filter.
 *  [gemGlyph] is the user's 💎 frontier glyph. Returns null when no
 *  source yields any point (nothing to export). */
internal fun buildValueViewHtml(
    data: ValueViewData,
    sources: List<RankSource>,
    combinedRows: List<RerankRow>,
    tournamentTotalRows: List<RerankRow>,
    defaultSourceKey: String?,
    gemGlyph: String
): String? {
    val report = data.report ?: return null
    val tabs = sources.mapNotNull { src ->
        val rows = rowsForSource(src, data, combinedRows, tournamentTotalRows)
        val points = buildValuePoints(report, rows, data.fanOutCostByAgentId)
        if (points.isEmpty()) null else ExportTab(src.key(), src.label, points)
    }
    if (tabs.isEmpty()) return null
    val defaultKey = tabs.firstOrNull { it.key == defaultSourceKey }?.key ?: tabs.first().key
    val title = data.reportTitle?.takeIf { it.isNotBlank() } ?: "Report"
    val gem = esc(gemGlyph)

    val sb = StringBuilder(64 * 1024)
    sb.append(
        """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Value view - ${esc(title)}</title>
        <style>
        :root { color-scheme: dark; }
        * { box-sizing: border-box; }
        body { margin: 0; padding: 16px; background: #000; color: #fff;
               font: 14px/1.45 -apple-system, "Segoe UI", Roboto, sans-serif; }
        h1 { font-size: 20px; margin: 0 0 2px; }
        .subtitle { color: #FF9800; font-size: 14px; margin-bottom: 10px; }
        .caption { color: #909090; font-size: 11px; margin: 8px 0; }
        .tabs { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0; }
        .tab { border: 0; border-radius: 8px; padding: 8px 12px; font-size: 13px;
               background: #2A2A3A; color: #fff; cursor: pointer; }
        .tab.active { background: #8B5CF6; color: #000; font-weight: bold; }
        .pane { display: none; }
        .pane.active { display: block; }
        .chart { background: #2A2A3A; border-radius: 12px; padding: 10px;
                 cursor: pointer; margin-bottom: 12px; }
        .chart svg { display: block; width: 100%; height: auto; }
        .best-line { color: #4CAF50; font-size: 13px; font-weight: 600; margin: 0 0 10px; }
        table.vrows { width: 100%; border-collapse: collapse; }
        table.vrows td { background: #2A2A3A; padding: 8px 12px; font-size: 13px;
                         border-top: 6px solid #000; vertical-align: middle; }
        table.vrows td:first-child { border-radius: 10px 0 0 10px; }
        table.vrows td:last-child { border-radius: 0 10px 10px 0; text-align: right;
                                    font-size: 12px; font-weight: 600; white-space: nowrap; }
        .vname { font-weight: 600; }
        .vnums { color: #CCCCCC; font-family: ui-monospace, Menlo, Consolas, monospace;
                 font-size: 12px; }
        .b-best { color: #4CAF50; } .b-pareto { color: #6B9BFF; } .b-dom { color: #909090; }
        #overlay { display: none; position: fixed; inset: 0; background: #000;
                   z-index: 1000; padding: 12px; }
        #overlay.open { display: flex; flex-direction: column; }
        #overlay .hint { color: #909090; font-size: 12px; text-align: center; margin-bottom: 6px; }
        #overlay svg { flex: 1; width: 100%; height: 100%; }
        </style>
        </head>
        <body>
        """.trimIndent()
    )
    sb.append("\n<h1>Value view</h1>\n")
    sb.append("<div class=\"subtitle\">").append(esc(title)).append("</div>\n")
    val fanOutNote = if (data.includesFanOut)
        " Current answer attempt only; historical and unknown attempt costs are excluded." else ""
    sb.append("<div class=\"caption\">Current-attempt cost × rubric score. Only recorded, unchanged sources are compared; Combined requires common coverage. ")
        .append(gem).append(" = Pareto frontier; dimmed = dominated (another model is at least as good for less).")
        .append(esc(fanOutNote)).append(" Click a chart to view it full screen.</div>\n")

    // Tabs row — one button per ranking source.
    sb.append("<div class=\"tabs\">\n")
    tabs.forEach { t ->
        sb.append("<button class=\"tab\" data-key=\"").append(esc(t.key))
            .append("\" onclick=\"showTab('").append(jsEsc(t.key)).append("')\">")
            .append(esc(t.label)).append("</button>\n")
    }
    sb.append("</div>\n")

    // One pane per tab: chart placeholder + frontier line + ranked list,
    // sorted exactly like the screen (Pareto frontier, then Pareto, then quality).
    tabs.forEach { t ->
        sb.append("<section class=\"pane\" data-key=\"").append(esc(t.key)).append("\">\n")
        sb.append("<div class=\"chart\" data-key=\"").append(esc(t.key))
            .append("\" onclick=\"openFull('").append(jsEsc(t.key)).append("')\"></div>\n")
        t.points.firstOrNull { it.bestValue }?.let { b ->
            sb.append("<div class=\"best-line\">").append(gem).append(" Pareto frontier: ")
                .append(esc(b.provider)).append(" · ").append(esc(b.modelShort))
                .append(" — score ").append(esc(formatScore(b.quality)))
                .append(" at ").append(esc(fmtCentsValue(b.costCents))).append("</div>\n")
        }
        val sorted = t.points.sortedWith(
            compareByDescending<ValuePoint> { it.bestValue }
                .thenBy { it.dominated }
                .thenByDescending { it.quality }
        )
        sb.append("<table class=\"vrows\">\n")
        sorted.forEach { p ->
            val (badge, cls) = when {
                p.bestValue -> "$gem Pareto frontier" to "b-best"
                !p.dominated -> "Pareto" to "b-pareto"
                else -> "dominated" to "b-dom"
            }
            sb.append("<tr><td><div class=\"vname\">").append(esc(p.provider)).append(" · ")
                .append(esc(p.modelShort)).append("</div><div class=\"vnums\">")
                .append(esc(fmtCentsValue(p.costCents))).append(" &nbsp;·&nbsp; score ")
                .append(esc(formatScore(p.quality)))
                .append("</div>")
                .append(if (p.evidence.isNotBlank()) "<details><summary>Score basis</summary><p>" + esc(p.evidence).replace("\n","<br>") + "</p></details>" else "")
                .append("</td><td class=\"").append(cls).append("\">")
                .append(badge).append("</td></tr>\n")
        }
        sb.append("</table>\n</section>\n")
    }
    sb.append("<div id=\"overlay\" onclick=\"closeFull()\"></div>\n")

    // Data blob + renderer. jsonEsc turns every `<` into <, so no
    // model/provider/language string can ever close the script element.
    sb.append("<script>\nconst VV = {\"defaultKey\":\"").append(jsonEsc(defaultKey)).append("\",\"tabs\":[")
    tabs.forEachIndexed { ti, t ->
        if (ti > 0) sb.append(',')
        sb.append("{\"key\":\"").append(jsonEsc(t.key)).append("\",\"label\":\"").append(jsonEsc(t.label)).append("\",\"points\":[")
        t.points.forEachIndexed { pi, p ->
            if (pi > 0) sb.append(',')
            sb.append("{\"provider\":\"").append(jsonEsc(p.provider))
                .append("\",\"model\":\"").append(jsonEsc(p.modelShort))
                .append("\",\"cost\":").append(jsonNum(p.costCents))
                .append(",\"q\":").append(jsonNum(p.quality))
                .append(",\"dom\":").append(p.dominated)
                .append(",\"best\":").append(p.bestValue).append('}')
        }
        sb.append("]}")
    }
    sb.append("]};\n")
    sb.append(VALUE_VIEW_JS)
    sb.append("\n</script>\n</body>\n</html>\n")
    return sb.toString()
}

/** Stage [html] in the share cache and open the system share sheet. */
internal fun exportValueViewHtml(context: Context, html: String, reportTitle: String?) {
    val safeTitle = (reportTitle ?: "report")
        .replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(40)
        .ifBlank { "report" }
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    shareExportText(
        context,
        fileName = "ai_value_view_${safeTitle}_$ts.html",
        mimeType = "text/html",
        chooserTitle = "Share Value view (HTML)",
        content = html
    )
}

// ===== Escaping / formatting =====

/** HTML-escape for text + attribute contexts. */
private fun esc(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
        '"' -> append("&quot;"); '\'' -> append("&#39;")
        else -> append(c)
    }
}

/** Escape for a JSON string inside an inline <script>: control chars,
 *  quotes, backslash — and `<` → < so `</script>`/`<!--` can never
 *  appear, whatever a provider/model name contains. */
private fun jsonEsc(s: String): String = buildString(s.length) {
    for (c in s) when {
        c == '\\' -> append("\\\\")
        c == '"' -> append("\\\"")
        c == '<' -> append("\\u003C")
        c == '\n' -> append("\\n")
        c == '\r' -> append("\\r")
        c == '\t' -> append("\\t")
        c < ' ' -> append(String.format(Locale.US, "\\u%04X", c.code))
        else -> append(c)
    }
}

/** Escape for a single-quoted JS string inside an onclick attribute —
 *  the keys are app-generated (`rerank`, `tournament:ELO`, `transrank:<uuid>`)
 *  but escape defensively anyway. */
private fun jsEsc(s: String): String = jsonEsc(s).replace("'", "\\'")

/** Finite JSON number (NaN/Infinity are not valid JSON). */
private fun jsonNum(d: Double): String =
    if (d.isFinite()) String.format(Locale.US, "%.6f", d).trimEnd('0').trimEnd('.') else "0"

/** Cents with 4 decimals — what the screen shows via
 *  `formatCents(costCents / 100.0)` (UiFormatting.kt). */
private fun fmtCentsValue(cents: Double): String = String.format(Locale.US, "%.4f", cents)

// ===== The page's JS (plain, no libs) =====
//
// renderChart mirrors the native ValueScatterCanvas: padded plot area, 10%
// range padding on both axes so no point sits on an axis, 3 ticks per axis
// (min / mid / max of the REAL data values), best = green dot + translucent
// glow, dominated = small dim dot, else orange; model label right of the
// dot with a simple vertical stagger on overlap; axis titles "Cost" below
// and the ranking label rotated up the left edge.
private val VALUE_VIEW_JS = """
function fmtCents(c) { return c.toFixed(4); }
function fmtScore(q) { return q === Math.trunc(q) ? String(q) : q.toFixed(1); }
function renderChart(el, tab, big) {
  const W = big ? 1200 : 800, H = big ? 800 : 420;
  const padL = big ? 130 : 100, padR = big ? 60 : 48, padT = big ? 30 : 16, padB = big ? 70 : 52;
  const fs = big ? 17 : 11, tfs = big ? 19 : 12;
  const plotW = W - padL - padR, plotH = H - padT - padB;
  const pts = tab.points;
  let minC = Math.min(...pts.map(p => p.cost)), maxC = Math.max(...pts.map(p => p.cost));
  let minQ = Math.min(...pts.map(p => p.q)),    maxQ = Math.max(...pts.map(p => p.q));
  const cPad = (maxC - minC) > 1e-9 ? (maxC - minC) * 0.10 : Math.max(Math.abs(maxC) * 0.10, 0.5);
  const qPad = (maxQ - minQ) > 1e-9 ? (maxQ - minQ) * 0.10 : Math.max(Math.abs(maxQ) * 0.10, 0.5);
  const c0 = minC - cPad, c1 = maxC + cPad, q0 = minQ - qPad, q1 = maxQ + qPad;
  const X = c => padL + (c - c0) / (c1 - c0) * plotW;
  const Y = q => padT + (1 - (q - q0) / (q1 - q0)) * plotH;
  let s = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + W + ' ' + H + '">';
  // Axes.
  s += '<line x1="' + padL + '" y1="' + padT + '" x2="' + padL + '" y2="' + (padT + plotH) + '" stroke="#909090"/>';
  s += '<line x1="' + padL + '" y1="' + (padT + plotH) + '" x2="' + (padL + plotW) + '" y2="' + (padT + plotH) + '" stroke="#909090"/>';
  // 3 ticks per axis at the REAL data min / mid / max.
  [minC, (minC + maxC) / 2, maxC].forEach(c => {
    s += '<text x="' + X(c) + '" y="' + (padT + plotH + fs + 6) + '" fill="#909090" font-size="' + fs +
         '" text-anchor="middle">' + fmtCents(c) + '</text>';
  });
  [minQ, (minQ + maxQ) / 2, maxQ].forEach(q => {
    s += '<text x="' + (padL - 8) + '" y="' + (Y(q) + fs / 3) + '" fill="#909090" font-size="' + fs +
         '" text-anchor="end">' + fmtScore(q) + '</text>';
  });
  // Axis titles.
  s += '<text x="' + (padL + plotW / 2) + '" y="' + (H - 8) + '" fill="#CCCCCC" font-size="' + tfs +
       '" font-weight="bold" text-anchor="middle">Cost</text>';
  s += '<text x="' + tfs + '" y="' + (padT + plotH / 2) + '" fill="#CCCCCC" font-size="' + tfs +
       '" font-weight="bold" text-anchor="middle" transform="rotate(-90 ' + tfs + ' ' + (padT + plotH / 2) + ')">' +
       escHtml(tab.label) + '</text>';
  // Dots + labels (labels staggered when their baselines would collide).
  const placed = [];
  pts.forEach(p => {
    const x = X(p.cost), y = Y(p.q);
    const r = big ? (p.best ? 9 : p.dom ? 5.5 : 7) : (p.best ? 6 : p.dom ? 3.5 : 4.5);
    const col = p.best ? '#4CAF50' : p.dom ? '#909090' : '#FF9800';
    if (p.best) s += '<circle cx="' + x + '" cy="' + y + '" r="' + (r * 1.8) + '" fill="#4CAF50" opacity="0.30"/>';
    s += '<circle cx="' + x + '" cy="' + y + '" r="' + r + '" fill="' + col + '"/>';
    let ly = y + fs / 3;
    while (placed.some(q => Math.abs(q.y - ly) < fs + 2 && Math.abs(q.x - x) < (big ? 260 : 170))) ly += fs + 2;
    placed.push({ x: x, y: ly });
    s += '<text x="' + (x + r + 5) + '" y="' + ly + '" fill="' + (p.dom ? '#909090' : '#CCCCCC') +
         '" font-size="' + fs + '">' + escHtml(p.provider + ' · ' + p.model) + '</text>';
  });
  s += '</svg>';
  el.innerHTML = s;
}
function escHtml(s) {
  return s.replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}
function showTab(key) {
  document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.key === key));
  document.querySelectorAll('.pane').forEach(p => p.classList.toggle('active', p.dataset.key === key));
}
function openFull(key) {
  const tab = VV.tabs.find(t => t.key === key);
  if (!tab) return;
  const ov = document.getElementById('overlay');
  ov.innerHTML = '<div class="hint">Click or press Esc to close</div>';
  const holder = document.createElement('div');
  holder.style.flex = '1';
  ov.appendChild(holder);
  renderChart(holder, tab, true);
  ov.classList.add('open');
}
function closeFull() { document.getElementById('overlay').classList.remove('open'); }
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeFull(); });
VV.tabs.forEach(t => renderChart(document.querySelector('.chart[data-key="' + t.key + '"]'), t, false));
showTab(VV.defaultKey);
""".trimIndent()
