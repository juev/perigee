package org.evsyukov.perigee.library

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.LocalBook
import org.evsyukov.perigee.engine.Engine

/**
 * Reads what a book file says about itself and keeps its cover. A book that
 * does not name itself is titled by its file name. Null when the engine
 * cannot read the file.
 *
 * @param name the file name, ending as [format] does
 */
internal suspend fun readBook(
    context: Context, covers: Covers, file: ParcelFileDescriptor, name: String, format: String,
): LocalBook? {
    // Not closed here: the stream shares the descriptor, which the caller closes.
    val id = FileHash.partialMd5(FileInputStream(file.fileDescriptor).channel)
    val said = Engine.describe(context, file, name) ?: return null
    return LocalBook(
        id, format,
        title = said.title.ifBlank { name.dropLast(format.length + 1) },
        authors = said.authors, language = said.language, series = said.series, seriesNumber = said.seriesNumber,
        hasCover = said.cover?.let { image -> covers.save(id, image) } ?: false,
    )
}
