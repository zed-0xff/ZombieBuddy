package i18n

var messagesES = map[string]string{
	// ── Aplicación ──
	"app.title":              "Instalador de ZombieBuddy para Windows v%s",
	"app.separator":          "---------------------------------------",
	"app.press_enter":        "Presione Enter para salir...",
	"app.install_complete":   "¡Instalación completa! Ahora puede iniciar Steam y ejecutar Project Zomboid.",
	"app.uninstall_complete": "¡Desinstalación completa! Ahora puede iniciar Steam y ejecutar Project Zomboid.",
	"app.nothing_changed":    "No se realizaron cambios.",
	"app.okay_nothing":       "De acuerdo, no se modificó nada.",
	"app.errors_encountered": "%s encontró errores. Revise los mensajes anteriores.",

	// ── Pregunta: acción principal ──
	"prompt.action.title":   "¿Qué puedo hacer por usted?",
	"prompt.action.line1":   "  1) Instalar ZombieBuddy",
	"prompt.action.line2":   "  2) Desinstalar ZombieBuddy",
	"prompt.action.line3":   "  3) Nada, ¡gracias!",
	"prompt.action.hint":    "Cualquier cambio en el sistema se mostrará para confirmación antes de aplicarse.",
	"prompt.action.prompt":  "Elija 1, 2 o 3: ",
	"prompt.action.eof_err": "no se seleccionó ninguna acción",
	"prompt.action.invalid": "Elija 1 para instalar, 2 para desinstalar o 3 para no hacer nada.",

	// ── Pregunta: objetivos de instalación ──
	"prompt.targets.title":   "¿Qué modo de inicio debe modificar ZombieBuddy?",
	"prompt.targets.line1":   "  1) Ambos",
	"prompt.targets.line2":   "  2) Inicio normal",
	"prompt.targets.line3":   "  3) Inicio alternativo",
	"prompt.targets.prompt":  "Elija 1, 2 o 3: ",
	"prompt.targets.eof_err": "no se seleccionó ningún modo de inicio",
	"prompt.targets.invalid": "Elija 1, 2 o 3.",

	// ── Pregunta: instalación normal ──
	"prompt.normal.title":   "¿Cómo se debe modificar el inicio normal?",
	"prompt.normal.line1":   "  1) Ambos",
	"prompt.normal.line2":   "  2) ProjectZomboid64.json",
	"prompt.normal.line3":   "  3) Opciones de inicio de Steam",
	"prompt.normal.prompt":  "Elija 1, 2 o 3: ",
	"prompt.normal.eof_err": "no se seleccionó ninguna modificación de inicio normal",
	"prompt.normal.invalid": "Elija 1, 2 o 3.",

	// ── Confirmación ──
	"confirm.no_changes":        "No se necesitan cambios en el sistema.",
	"confirm.will_make_changes": "ZombieBuddy realizará estos cambios:",
	"confirm.prompt":            "¿Continuar? [s/N]: ",

	// ── Nombres de acciones ──
	"action.install":   "Instalación",
	"action.uninstall": "Desinstalación",
	"action.nothing":   "Nada",
	"action.operation": "Operación",

	// ── Mensajes de instalación ──
	"install.err_select_targets": "Error al seleccionar los objetivos de inicio: %v",
	"install.err_read_confirm":   "Error al leer la confirmación: %v",
	"install.cancelled":          "Instalación cancelada.",
	"install.err_copy_core":      "Error al copiar los archivos principales: %v",
	"install.success_copy":       "zbNative.dll y ZombieBuddy.jar instalados correctamente",
	"install.err_json":           "Error al actualizar ProjectZomboid64.json: %v",
	"install.json_updated":       "ProjectZomboid64.json actualizado para el modo de inicio normal",
	"install.json_already":       "ProjectZomboid64.json ya contiene el agente ZombieBuddy.",
	"install.err_batch":          "Error al actualizar ProjectZomboid64.bat: %v",
	"install.batch_updated":      "ProjectZomboid64.bat actualizado para el modo de inicio alternativo",
	"install.batch_already":      "ProjectZomboid64.bat ya contiene el agente ZombieBuddy.",
	"install.err_steam":          "Error al actualizar las opciones de inicio de Steam: %v",

	// ── Mensajes de desinstalación ──
	"uninstall.err_plan":      "Error al planificar la desinstalación: %v",
	"uninstall.nothing":       "Nada que desinstalar.",
	"uninstall.cancelled":     "Desinstalación cancelada.",
	"uninstall.err_apply":     "Error al aplicar los cambios de desinstalación: %v",
	"uninstall.err_json":      "actualizando ProjectZomboid64.json: %v",
	"uninstall.json_removed":  `"%s" eliminado de ProjectZomboid64.json`,
	"uninstall.err_batch":     "actualizando ProjectZomboid64.bat: %v",
	"uninstall.batch_removed": `"%s" eliminado de ProjectZomboid64.bat`,
	"uninstall.err_rm_core":   "eliminando archivos principales: %v",
	"uninstall.err_rm_steam":  "eliminando opciones de inicio de Steam: %v",

	// ── Detección de rutas ──
	"detect.err_steam":   "Error al detectar Steam: %v",
	"detect.path_format": "[.] %-*s se encuentra en %s",
	"detect.err_pz":      "Error al detectar Project Zomboid: %v",
	"detect.err_zb":      "Error al detectar el mod ZombieBuddy: %v",
	"detect.steam_label": "Steam",
	"detect.pz_label":    "PZ",
	"detect.zb_label":    "ZB",

	// ── Vista previa / planificación ──
	"preview.copy":         `copiar "%s\%s"    a "%s\\"`,
	"preview.add_json":     `agregar "%s" a "%s"`,
	"preview.add_steam":    `agregar "%s" a las opciones de inicio de Steam de PZ`,
	"preview.remove":       `eliminar "%s" de "%s"`,
	"preview.remove_steam": `eliminar "%s" de las opciones de inicio de Steam de PZ`,
	"preview.delete":       `eliminar "%s"`,

	// ── Cierre de Steam ──
	"steam.registry_error":        "no se pudo encontrar la clave de registro de Steam",
	"steam.registry_windows_only": "la búsqueda en el registro de Steam solo es compatible con Windows",
	"steam.running":               "Steam se está ejecutando actualmente.",
	"steam.close_instructions1":   "    Cierre Steam completamente antes de continuar.",
	"steam.close_instructions2":   "    (Clic derecho en Steam en la bandeja del sistema -> Salir)",
	"steam.press_enter_after":     "Presione Enter después de cerrar Steam...",
	"steam.now_closed":            "Steam está cerrado. Continuando con %s...",
	"steam.launch_opt_update":     "actualización de opciones de inicio",

	// ── Errores de ruta ──
	"path.pz_not_found": "no se pudo encontrar la instalación de Project Zomboid",
	"path.zb_not_found": "Asegúrese de estar suscrito a https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853",

	// ── Errores VDF / biblioteca ──
	"vdf.invalid_library":   "libraryfolders.vdf no válido",
	"vdf.app_not_found":     "aplicación no encontrada en ninguna biblioteca de Steam",
	"vdf.no_config":         "no se pudo crear la configuración de inicio de Project Zomboid",
	"vdf.already_has_agent": "Las opciones de inicio ya contienen el agente ZombieBuddy.",
	"vdf.no_appstate":       "no se pudo encontrar AppState para %s",

	// ── Otros errores ──
	"err.check_json":            "verificando ProjectZomboid64.json: %v",
	"err.check_batch":           "verificando ProjectZomboid64.bat: %v",
	"err.windows_only":          "Error: este instalador solo está diseñado para Windows.",
	"err.source_not_found":      "no se pudo encontrar el origen %s",
	"err.copy_failed":           "error al copiar %s: %v",
	"err.remove_failed":         "error al eliminar %s: %v",
	"err.nav_not_map":           "la clave %s no es un map",
	"err.nav_key_not_found":     "clave %s no encontrada",
	"err.unknown_launch_mode":   "modo de inicio desconocido",
	"err.unknown_normal_target": "objetivo de inicio normal desconocido",

	// ── Información de copia / eliminación ──
	"copy.copying":               `copiando "%s" a "%s"`,
	"remove.removed":             `%s eliminado`,
	"remove.steam_failed_remove": "error al eliminar las opciones de inicio de %s: %v",
	"remove.steam_removed":       "Opciones de inicio eliminadas de %s",

	// ── Mensajes de usuario de Steam ──
	"steam.updated_user":  "Opciones de inicio actualizadas para el usuario %s",
	"steam.failed_user":   "Error al actualizar las opciones de inicio para el usuario %s: %v",
	"steam.no_users":      "no se encontraron configuraciones de usuario para actualizar",
	"steam.skipping_user": "omitiendo opciones de inicio de Steam para el usuario %s: %v",
}
