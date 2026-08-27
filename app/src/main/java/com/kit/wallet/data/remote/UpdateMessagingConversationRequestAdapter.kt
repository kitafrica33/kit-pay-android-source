package com.kit.wallet.data.remote

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.ToJson

/**
 * Writes [UpdateMessagingConversationRequest] so a cleared description actually clears.
 *
 * The server reads an absent `description` as "leave it alone" and `"description": null` as
 * "remove it". Moshi's reflective adapter drops nulls, which can only say the first. Null
 * serialization is turned on for the one value that needs it and restored immediately, the
 * same way [UpdateProfileRequestAdapter] clears a username.
 */
class UpdateMessagingConversationRequestAdapter {
    @ToJson
    fun toJson(writer: JsonWriter, request: UpdateMessagingConversationRequest) {
        writer.beginObject()
        writer.name("description")
        if (request.description == null) {
            val serializeNulls = writer.serializeNulls
            writer.serializeNulls = true
            writer.nullValue()
            writer.serializeNulls = serializeNulls
        } else {
            writer.value(request.description)
        }
        writer.endObject()
    }

    /** Never received: the conversation endpoints answer with a conversation. */
    @FromJson
    fun fromJson(reader: JsonReader): UpdateMessagingConversationRequest {
        reader.skipValue()
        throw UnsupportedOperationException("UpdateMessagingConversationRequest is only ever sent")
    }
}
