package com.kaori.codex.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Hosts Android's user-facing document picker for one correlated bridge request. */
class CodexFilePickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "pick"
        val picker = when {
            kind == "save" -> Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.getBooleanExtra(EXTRA_DIRECTORY, false) -> Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            else -> Intent(Intent.ACTION_OPEN_DOCUMENT)
        }.apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = intent.getStringExtra(EXTRA_MIME) ?: "*/*"
            if (kind != "save") {
                putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    intent.getBooleanExtra(EXTRA_ALLOW_MULTIPLE, false),
                )
                val mimeTypes = intent.getStringExtra(EXTRA_MIME_TYPES)
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?.toTypedArray()
                if (!mimeTypes.isNullOrEmpty()) {
                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                }
            } else {
                putExtra(Intent.EXTRA_TITLE, intent.getStringExtra(EXTRA_TITLE) ?: "Codex export")
            }
        }
        startActivityForResult(picker, PICKER_REQUEST_CODE)
    }

    @Deprecated("Activity result API is sufficient for this private bridge host.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICKER_REQUEST_CODE) return
        CodexFileOperations.complete(
            this,
            intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty(),
            intent.getStringExtra(EXTRA_KIND) ?: "pick",
            intent.getStringExtra(EXTRA_SOURCE_PATH),
            data,
            resultCode,
        )
        finish()
    }

    private companion object {
        const val PICKER_REQUEST_CODE = 2101
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_KIND = "kind"
        const val EXTRA_SOURCE_PATH = "sourcePath"
        const val EXTRA_DIRECTORY = "directory"
        const val EXTRA_ALLOW_MULTIPLE = "allowMultiple"
        const val EXTRA_MIME = "mime"
        const val EXTRA_MIME_TYPES = "mimeTypes"
        const val EXTRA_TITLE = "title"
    }
}

