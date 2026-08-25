package com.kit.wallet.data.remote

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.ToJson

/**
 * Writes [UpdateProfileRequest] so that an omitted field and a cleared field stay different.
 *
 * Moshi drops nulls when serializing, which is exactly right for "leave this alone" and exactly
 * wrong for "drop my username" — the server distinguishes an absent `tag` from `"tag": null`, and
 * the reflective adapter cannot express the second. Null serialization is turned on for the one
 * value that needs it and restored immediately, so nothing else on the wire changes shape.
 */
class UpdateProfileRequestAdapter {
    @ToJson
    fun toJson(writer: JsonWriter, request: UpdateProfileRequest) {
        writer.beginObject()
        request.name?.let {
            writer.name("name")
            writer.value(it)
        }
        when {
            request.clearUsername -> {
                writer.name("tag")
                val serializeNulls = writer.serializeNulls
                writer.serializeNulls = true
                writer.nullValue()
                writer.serializeNulls = serializeNulls
            }
            request.tag != null -> {
                writer.name("tag")
                writer.value(request.tag)
            }
        }
        writer.endObject()
    }

    /** Never received: the profile endpoints answer with a user, not with the request. */
    @FromJson
    fun fromJson(reader: JsonReader): UpdateProfileRequest {
        reader.skipValue()
        throw UnsupportedOperationException("UpdateProfileRequest is only ever sent")
    }
}
