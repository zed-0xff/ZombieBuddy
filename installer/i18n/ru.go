package i18n

var messagesRU = map[string]string{
	// ── Приложение ──
	"app.title":              "Установщик ZombieBuddy для Windows v%s",
	"app.separator":          "--------------------------------------",
	"app.press_enter":        "Нажмите Enter для выхода...",
	"app.install_complete":   "Установка завершена! Теперь можно запустить Steam и Project Zomboid.",
	"app.uninstall_complete": "Удаление завершено! Теперь можно запустить Steam и Project Zomboid.",
	"app.nothing_changed":    "Ничего не изменено.",
	"app.okay_nothing":       "Хорошо, ничего не изменено.",
	"app.errors_encountered": "%s завершился с ошибками. Просмотрите сообщения выше.",

	// ── Запрос: основное действие ──
	"prompt.action.title":   "Чем я могу помочь?",
	"prompt.action.line1":   "  1) Установить ZombieBuddy",
	"prompt.action.line2":   "  2) Удалить ZombieBuddy",
	"prompt.action.line3":   "  3) Ничего, спасибо!",
	"prompt.action.hint":    "Все системные изменения будут показаны для подтверждения перед применением.",
	"prompt.action.prompt":  "Выберите 1, 2 или 3: ",
	"prompt.action.eof_err": "действие не выбрано",
	"prompt.action.invalid": "Выберите 1 для установки, 2 для удаления или 3, чтобы ничего не делать.",

	// ── Запрос: цели установки ──
	"prompt.targets.title":   "Какой режим запуска должен изменять ZombieBuddy?",
	"prompt.targets.line1":   "  1) Оба",
	"prompt.targets.line2":   "  2) Обычный запуск",
	"prompt.targets.line3":   "  3) Альтернативный запуск",
	"prompt.targets.prompt":  "Выберите 1, 2 или 3: ",
	"prompt.targets.eof_err": "режим запуска не выбран",
	"prompt.targets.invalid": "Пожалуйста, выберите 1, 2 или 3.",

	// ── Запрос: обычная установка ──
	"prompt.normal.title":   "Как следует изменить обычный запуск?",
	"prompt.normal.line1":   "  1) Оба",
	"prompt.normal.line2":   "  2) ProjectZomboid64.json",
	"prompt.normal.line3":   "  3) Параметры запуска Steam",
	"prompt.normal.prompt":  "Выберите 1, 2 или 3: ",
	"prompt.normal.eof_err": "изменение обычного запуска не выбрано",
	"prompt.normal.invalid": "Пожалуйста, выберите 1, 2 или 3.",

	// ── Подтверждение ──
	"confirm.no_changes":        "Системные изменения не требуются.",
	"confirm.will_make_changes": "ZombieBuddy внесёт следующие изменения:",
	"confirm.prompt":            "Продолжить? [y/N]: ",

	// ── Названия действий ──
	"action.install":   "Установка",
	"action.uninstall": "Удаление",
	"action.nothing":   "Ничего",
	"action.operation": "Операция",

	// ── Сообщения установки ──
	"install.err_select_targets": "Ошибка при выборе целей запуска: %v",
	"install.err_read_confirm":   "Ошибка при чтении подтверждения: %v",
	"install.cancelled":          "Установка отменена.",
	"install.err_copy_core":      "Ошибка при копировании основных файлов: %v",
	"install.success_copy":       "zbNative.dll и ZombieBuddy.jar успешно установлены",
	"install.err_json":           "Ошибка при обновлении ProjectZomboid64.json: %v",
	"install.json_updated":       "ProjectZomboid64.json обновлён для обычного режима запуска",
	"install.json_already":       "ProjectZomboid64.json уже содержит агент ZombieBuddy.",
	"install.err_batch":          "Ошибка при обновлении ProjectZomboid64.bat: %v",
	"install.batch_updated":      "ProjectZomboid64.bat обновлён для альтернативного режима запуска",
	"install.batch_already":      "ProjectZomboid64.bat уже содержит агент ZombieBuddy.",
	"install.err_steam":          "Ошибка при обновлении параметров запуска Steam: %v",

	// ── Сообщения удаления ──
	"uninstall.err_plan":      "Ошибка при планировании удаления: %v",
	"uninstall.nothing":       "Нечего удалять.",
	"uninstall.cancelled":     "Удаление отменено.",
	"uninstall.err_apply":     "Ошибка при применении изменений удаления: %v",
	"uninstall.err_json":      "обновление ProjectZomboid64.json: %v",
	"uninstall.json_removed":  `"%s" удалён из ProjectZomboid64.json`,
	"uninstall.err_batch":     "обновление ProjectZomboid64.bat: %v",
	"uninstall.batch_removed": `"%s" удалён из ProjectZomboid64.bat`,
	"uninstall.err_rm_core":   "удаление основных файлов: %v",
	"uninstall.err_rm_steam":  "удаление параметров запуска Steam: %v",

	// ── Обнаружение путей ──
	"detect.err_steam":   "Ошибка при обнаружении Steam: %v",
	"detect.path_format": "[.] %-*s находится в %s",
	"detect.err_pz":      "Ошибка при обнаружении Project Zomboid: %v",
	"detect.err_zb":      "Ошибка при обнаружении мода ZombieBuddy: %v",
	"detect.steam_label": "Steam",
	"detect.pz_label":    "PZ",
	"detect.zb_label":    "ZB",

	// ── Предпросмотр / план ──
	"preview.copy":         `копировать "%s\%s"    в "%s\\"`,
	"preview.add_json":     `добавить "%s" в "%s"`,
	"preview.add_steam":    `добавить "%s" в параметры запуска Steam для PZ`,
	"preview.remove":       `удалить "%s" из "%s"`,
	"preview.remove_steam": `удалить "%s" из параметров запуска Steam для PZ`,
	"preview.delete":       `удалить "%s"`,

	// ── Закрытие Steam ──
	"steam.registry_error":        "не удалось найти ключ реестра Steam",
	"steam.registry_windows_only": "поиск в реестре Steam поддерживается только в Windows",
	"steam.running":               "Steam сейчас запущен.",
	"steam.close_instructions1":   "    Пожалуйста, полностью закройте Steam перед продолжением.",
	"steam.close_instructions2":   "    (Правый клик по Steam в системном трее -> Выход)",
	"steam.press_enter_after":     "Нажмите Enter после закрытия Steam...",
	"steam.now_closed":            "Steam закрыт. Продолжение %s...",
	"steam.launch_opt_update":     "обновление параметров запуска",

	// ── Ошибки путей ──
	"path.pz_not_found": "не удалось найти установку Project Zomboid",
	"path.zb_not_found": "Убедитесь, что вы подписаны на https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853",

	// ── Ошибки VDF / библиотеки ──
	"vdf.invalid_library":   "недопустимый libraryfolders.vdf",
	"vdf.app_not_found":     "приложение не найдено ни в одной библиотеке Steam",
	"vdf.no_config":         "не удалось создать конфигурацию запуска Project Zomboid",
	"vdf.already_has_agent": "Параметры запуска уже содержат агент ZombieBuddy.",
	"vdf.no_appstate":       "не удалось найти AppState для %s",

	// ── Прочие ошибки ──
	"err.check_json":            "проверка ProjectZomboid64.json: %v",
	"err.check_batch":           "проверка ProjectZomboid64.bat: %v",
	"err.windows_only":          "Ошибка: этот установщик предназначен только для Windows.",
	"err.source_not_found":      "источник %s не найден",
	"err.copy_failed":           "не удалось скопировать %s: %v",
	"err.remove_failed":         "не удалось удалить %s: %v",
	"err.nav_not_map":           "ключ %s не является map",
	"err.nav_key_not_found":     "ключ %s не найден",
	"err.unknown_launch_mode":   "неизвестный режим запуска",
	"err.unknown_normal_target": "неизвестная цель обычного запуска",

	// ── Информация о копировании / удалении ──
	"copy.copying":               `копирование "%s" в "%s"`,
	"remove.removed":             `%s удалён`,
	"remove.steam_failed_remove": "не удалось удалить параметры запуска из %s: %v",
	"remove.steam_removed":       "Параметры запуска удалены из %s",

	// ── Сообщения пользователя Steam ──
	"steam.updated_user":  "Параметры запуска обновлены для пользователя %s",
	"steam.failed_user":   "Не удалось обновить параметры запуска для пользователя %s: %v",
	"steam.no_users":      "конфигурации пользователей для обновления не найдены",
	"steam.skipping_user": "пропуск параметров запуска Steam для пользователя %s: %v",
}
