package io.legado.app.ui.book.character

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.readaloud.ReadAloudConfigChangeNotifier
import io.legado.app.help.readaloud.speech.SpeechVoiceCatalogRepository
import io.legado.app.ui.book.character.compose.CharacterCardScreen
import io.legado.app.ui.book.character.compose.CharacterSpeechEngineUi
import io.legado.app.ui.host.setV5Content
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 角色卡 V5 脚手架迁移：BaseActivity+ComposeView → AppCompatActivity+setV5Content。
 */
class BookCharacterCardActivity : AppCompatActivity() {

    private var bookUrl: String = ""
    private var characterBookKey: String = ""
    private var characterId: Long = 0L
    private var character by mutableStateOf<BookCharacter?>(null)
    private var speechEngines by mutableStateOf<List<CharacterSpeechEngineUi>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookUrl = intent.getStringExtra(BookCharacterManageActivity.EXTRA_BOOK_URL).orEmpty()
        characterBookKey = intent.getStringExtra(BookCharacterManageActivity.EXTRA_CHARACTER_BOOK_KEY).orEmpty()
        characterId = intent.getLongExtra(BookCharacterManageActivity.EXTRA_CHARACTER_ID, 0L)
        setV5Content {
            CharacterCardScreen(
                character = character,
                onBack = ::finish,
                onEdit = ::openEdit,
                speechEngines = speechEngines,
                onSpeechRouteChange = ::updateSpeechRoute
            )
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        if (characterId <= 0L) {
            toastOnUi("角色不存在")
            finish()
            return
        }
        lifecycleScope.launch {
            val data = withContext(IO) {
                appDb.bookCharacterDao.getCharacter(characterId) to
                    SpeechVoiceCatalogRepository
                        .allGroups(applicationContext, appDb.httpTTSDao.all)
                        .map { CharacterSpeechEngineUi(it) }
            }
            val item = data.first
            if (item == null) {
                toastOnUi("角色不存在")
                finish()
                return@launch
            }
            character = item
            speechEngines = data.second
        }
    }

    private fun updateSpeechRoute(routeJson: String) {
        val item = character ?: return
        lifecycleScope.launch {
            val updated = item.copy(
                speechRouteJson = routeJson.trim(),
                updatedAt = System.currentTimeMillis()
            )
            withContext(IO) {
                appDb.bookCharacterDao.updateCharacter(updated)
            }
            ReadAloudConfigChangeNotifier.notifySpeech()
            character = updated
            toastOnUi("已更新角色配音")
        }
    }

    private fun openEdit() {
        val item = character ?: return
        startActivity<BookCharacterEditActivity> {
            putExtra(BookCharacterManageActivity.EXTRA_BOOK_URL, bookUrl.ifBlank { item.bookUrl })
            putExtra(BookCharacterManageActivity.EXTRA_CHARACTER_BOOK_KEY, characterBookKey.ifBlank { item.bookUrl })
            putExtra(BookCharacterManageActivity.EXTRA_CHARACTER_ID, item.id)
        }
    }
}
