package com.celoext.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.celoext.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Skills are folders with SKILL.md in ~/.config/opencode/skills, the same format as Claude, ChatGPT and Codex:
 * YAML with name and description at the top, instructions below. The agent loads a skill itself when the description fits,
 * or when the user types "/name".
 */
object Skills {
    const val DIR = "/root/.config/opencode/skills"

    data class Skill(val name: String, val description: String, val body: String, val files: Int, val dir: File)

    fun dir(): File = Graph.paths.host(DIR)

    /** For AGENTS.md: so a skill can be asked for right in the chat. */
    val prompt = """
# Skills

Skills are folders in $DIR, one per skill, each with a SKILL.md:

    ---
    name: short-kebab-name
    description: One or two sentences: what it does AND when to use it (this is how you decide to load it).
    ---

    Instructions in markdown. Extra files (scripts, templates, references) may sit next to it.

- When the user asks to create, save or edit a skill, write or edit that SKILL.md with your file tools. Ask what it should do first if it is unclear. Keep the name lowercase with dashes.
- After saving, tell the user in one line that they can call it with /name. New skills appear in the app's "/" menu right away.
""".trim()

    fun local(): List<Skill> = dir().listFiles { f -> f.isDirectory }?.mapNotNull { d ->
        val md = File(d, "SKILL.md").takeIf { it.isFile } ?: return@mapNotNull null
        val (name, desc, body) = parse(runCatching { md.readText() }.getOrDefault(""))
        Skill(name ?: d.name, desc.orEmpty(), body, d.walkTopDown().count { it.isFile }, d)
    }?.sortedBy { it.name }.orEmpty()

    /** name, description and body from SKILL.md. Without a header, everything is the body. */
    fun parse(text: String): Triple<String?, String?, String> {
        val t = text.replace("\r\n", "\n").trimStart('﻿')
        if (!t.startsWith("---\n")) return Triple(null, null, t.trim())
        val end = t.indexOf("\n---", 4).takeIf { it > 0 } ?: return Triple(null, null, t.trim())
        val head = t.substring(4, end)
        val body = t.substring(end + 4).trimStart('-').trim()
        fun field(k: String): String? {
            val lines = head.lines()
            val i = lines.indexOfFirst { it.startsWith("$k:") }.takeIf { it >= 0 } ?: return null
            var v = lines[i].substringAfter(':').trim()
            // "description: >" and multi-line values
            if (v == ">" || v == "|" || v == ">-" || v == "|-") v = lines.drop(i + 1).takeWhile { it.startsWith(" ") }.joinToString(" ") { it.trim() }
            return v.removeSurrounding("\"").removeSurrounding("'").replace("\\\"", "\"").takeIf { it.isNotBlank() }
        }
        return Triple(field("name"), field("description"), body)
    }

    /**
     * Claude tolerates "description: text: with a colon" in the header, but OpenCode silently skips such a skill.
     * Rewrite name and description in quotes, leave the other header fields and the text alone.
     */
    fun normalize(md: File) {
        val t = runCatching { md.readText() }.getOrNull()?.replace("\r\n", "\n")?.trimStart('\uFEFF') ?: return
        if (!t.startsWith("---\n")) return
        val end = t.indexOf("\n---", 4).takeIf { it > 0 } ?: return
        val lines = t.substring(4, end).lines()
        val (name, desc, _) = parse(t)
        val keep = mutableListOf<String>()
        var skip = false
        for (l in lines) {
            val key = Regex("^([A-Za-z_-]+):").find(l)?.groupValues?.get(1)
            if (key != null) skip = key == "name" || key == "description"
            else if (l.isNotBlank() && !l.startsWith(" ") && !l.startsWith("\t")) skip = false
            if (!skip && l.isNotBlank()) keep += l
        }
        val head = buildString {
            append("name: ").append(slug(name ?: md.parentFile?.name ?: "skill")).append('\n')
            append("description: \"").append(desc.orEmpty().replace("\"", "\\\"")).append("\"\n")
            keep.forEach { append(it).append('\n') }
        }
        val fixed = "---\n" + head + t.substring(end + 1)
        if (fixed != t) md.writeText(fixed)
    }

    /** Fix the headers of skills already on disk (imported earlier or copied by hand). */
    fun repairAll(): Boolean {
        var any = false
        dir().listFiles { f -> f.isDirectory }?.forEach { d ->
            val md = File(d, "SKILL.md").takeIf { it.isFile } ?: return@forEach
            val before = md.readText(); normalize(md); if (md.readText() != before) any = true
        }
        if (any) changed()
        return any
    }

    fun slug(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9а-яё]+"), "-").trim('-').take(64).ifBlank { "skill" }

    fun render(name: String, description: String, body: String): String =
        "---\nname: $name\ndescription: \"" + description.replace("\n", " ").replace("\"", "\\\"").trim() + "\"\n---\n\n" + body.trim() + "\n"

    /** Save a skill; on rename the folder moves along with the other files. */
    fun save(name: String, description: String, body: String, oldName: String? = null): String {
        val n = slug(name)
        val target = File(dir(), n)
        if (oldName != null && oldName != n) {
            val old = File(dir(), oldName)
            require(!target.exists()) { "A skill named $n already exists" }
            if (old.exists()) old.renameTo(target)
        }
        target.mkdirs()
        File(target, "SKILL.md").writeText(render(n, description, body))
        changed()
        return n
    }

    fun delete(name: String) {
        // "..", "." or a path would pass a plain parentFile check: require a real child of the skills folder
        val base = dir().canonicalFile
        val f = File(base, name).canonicalFile
        if (name.isNotBlank() && '/' !in name && '\\' !in name && f.parentFile == base) f.deleteRecursively()
        changed()
    }

    /**
     * Import from a file: .zip / .skill (a Claude or ChatGPT archive with one or more folders with SKILL.md)
     * or a single .md. Returns the names of the added skills.
     */
    private const val MAX_IMPORT_BYTES = 16L * 1024 * 1024

    suspend fun import(ctx: Context, uri: Uri): List<String> = withContext(Dispatchers.IO) {
        val fileName = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        // Never readBytes() a whole pick into RAM: stream to a temp file with a hard cap.
        val tmp = File(ctx.cacheDir, "skill-import-" + fileName.hashCode())
        tmp.delete()
        try {
            var total = 0L
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_IMPORT_BYTES) error("The archive is too big (over ${MAX_IMPORT_BYTES / (1024 * 1024)} MB)")
                        output.write(buf, 0, n)
                    }
                }
            } ?: error("Could not read the file")
            val hdr = ByteArray(4)
            val r = tmp.inputStream().use { it.read(hdr, 0, 4) }
            val isZip = r == 4 && hdr[0] == 'P'.code.toByte() && hdr[1] == 'K'.code.toByte()
            val added = if (isZip) importZip(tmp, fileName) else listOf(importMarkdown(tmp.readText(), fileName.substringBeforeLast('.')))
            changed()
            added
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun importMarkdown(text: String, fallback: String): String {
        val (name, desc, body) = parse(text)
        require(body.isNotBlank()) { "The file is empty" }
        val n = slug(name ?: fallback.takeUnless { it.equals("SKILL", true) } ?: "imported-skill")
        val target = File(dir(), n).apply { mkdirs() }
        File(target, "SKILL.md").writeText(render(n, desc ?: body.lineSequence().firstOrNull { it.isNotBlank() }?.trim('#', ' ').orEmpty(), body))
        return n
    }

    private fun importZip(file: File, fileName: String): List<String> {
        val entries = LinkedHashMap<String, ByteArray>()
        var unpacked = 0L
        ZipInputStream(file.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val p = e.name.replace('\\', '/').trimStart('/')
                if (!e.isDirectory && !p.split('/').any { it == ".." } && !p.startsWith("__MACOSX/")) {
                    // read in chunks so a zip bomb is stopped by the cap instead of exhausting memory
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(1 shl 16)
                    while (true) {
                        val n = z.read(chunk)
                        if (n < 0) break
                        unpacked += n
                        if (unpacked > 64L * 1024 * 1024) error("The archive is too big")
                        buf.write(chunk, 0, n)
                    }
                    entries[p] = buf.toByteArray()
                }
                if (entries.size > 2000) error("The archive is too big")
            }
        }
        val roots = entries.keys.filter { it.substringAfterLast('/').equals("SKILL.md", true) }.map { it.substringBeforeLast('/', "") }
        if (roots.isEmpty()) {
            val md = entries.entries.firstOrNull { it.key.endsWith(".md", true) } ?: error("No SKILL.md in the archive")
            return listOf(importMarkdown(String(md.value), md.key.substringAfterLast('/').substringBeforeLast('.')))
        }
        return roots.map { root ->
            val prefix = if (root.isEmpty()) "" else "$root/"
            val md = entries.entries.first { it.key.startsWith(prefix) && it.key.removePrefix(prefix).equals("SKILL.md", true) }
            val (name, _, _) = parse(String(md.value))
            val n = slug(name ?: root.substringAfterLast('/').ifEmpty { fileName.substringBeforeLast('.') })
            val target = File(dir(), n)
            target.deleteRecursively()
            entries.filterKeys { it.startsWith(prefix) }.forEach { (p, data) ->
                val rel = p.removePrefix(prefix)
                // nested skills land in their own folders by themselves
                if (roots.any { r -> r != root && r.startsWith(prefix) && p.startsWith("$r/") }) return@forEach
                val out = File(target, if (rel.equals("SKILL.md", true)) "SKILL.md" else rel)
                if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) return@forEach
                out.parentFile?.mkdirs(); out.writeBytes(data)
            }
            normalize(File(target, "SKILL.md"))
            n
        }
    }

    /**
     * Instructions from ChatGPT (a custom GPT, "Custom instructions", a project) or from a Claude project, as a skill.
     */
    fun fromInstructions(name: String, description: String, instructions: String): String =
        save(name, description.ifBlank { "Use when the user asks for ${name.replace('-', ' ')}." }, instructions)

    /** The server reads skills when an instance starts: reload the list and the "/" menu. */
    private fun changed() {
        Graph.scope.launch {
            // leave a busy agent alone: skills are picked up on the next server start
            if (Graph.chats.statuses.value.values.none { it == "busy" }) runCatching { Graph.api.disposeAll() }
            runCatching { Graph.catalog.refresh() }
        }
    }
}
