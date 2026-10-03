# Historial de cambios

Todos los cambios relevantes de este proyecto se documentarán en este archivo.

El formato se basa en [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
y este proyecto sigue el [Versionado Semántico](https://semver.org/spec/v2.0.0.html).

## [1.4.0] — 2026-10-03

### Añadido

- **Sesiones de agentes en paneles divididos**: mueve las pestañas de sesión de Prism a divisiones a la derecha o en la parte inferior de la ventana de herramientas, reúnelas con Unsplit o crea una nueva sesión independiente directamente en una división.
- **Fuente y configuración del terminal**: Prism ahora sigue la configuración de fuente, espaciado entre líneas, zoom y demás preferencias del terminal del IDE.
- **Entrada Font Settings en el menú**: abre la configuración del terminal del IDE desde `Font Settings` en el menú de opciones (⋮). La configuración dedicada de fuente y esta opción requieren el build **251.25410+** (IntelliJ IDEA **2025.1.1+**). El engranaje de la barra abre la configuración de Prism.
- **Visor del changelog**: un botón junto a la configuración de Prism abre el historial completo de cambios con formato Markdown, en inglés, portugués brasileño o español, según el idioma de la interfaz de Prism. Los changelogs están incluidos en el plugin y disponibles sin conexión.

### Cambiado

- **Controles vinculados a la sesión**: los atajos del terminal y las acciones de la barra de herramientas ahora permanecen vinculados a la sesión a la que pertenecen cuando hay varios paneles visibles; las acciones del editor siguen dirigidas a la última sesión de Prism que recibió el foco.
- **Línea de tiempo de Diff para todo el proyecto**: las sesiones secuenciales mantienen interacciones numeradas independientes, mientras que las sesiones simultáneas comparten una única base de comparación y generan una entrada `Multiple chats` cuando todos los participantes quedan inactivos.

### Corregido

- **Teclas del terminal**: ESC, Shift+Enter, Ctrl+V (incluido el pegado de imágenes en Linux) y los atajos del agente funcionan con `Override IDE shortcuts` activado o desactivado.
- **Ciclo de vida del inicio**: cerrar una sesión mientras se está iniciando su PTY ya no permite conectar un terminal después de liberar la sesión ni dejar un proceso huérfano.
- **Coordinación de las instantáneas de Diff**: abrir otra sesión, recibir salida durante el inicio, seleccionar pestañas y actualizar manualmente ya no reinicia el trabajo pendiente ni añade números de interacción duplicados.

## [1.3.1] — 2026-08-26

### Corregido

- **Argumentos personalizados de la CLI**: la configuración de los comandos de Claude Code y Codex ahora acepta argumentos opcionales, incluidas las rutas entre comillas con espacios. Prism valida el ejecutable por separado y conserva cada argumento al iniciar la sesión (corrige #13).
- **Reordenación de pestañas de conversación**: arrastrar una pestaña a una nueva posición ya no la congela. La plataforma reordena una pestaña eliminando su contenido y volviéndolo a añadir en el nuevo índice, lo que Prism interpretaba como el cierre de la pestaña y usaba como señal para finalizar el proceso del agente de esa sesión — la pestaña reaparecía con el terminal todavía dibujado, pero sin nada ejecutándose detrás. El cierre de la sesión ahora está vinculado a la liberación efectiva de la pestaña, que solo ocurre cuando se cierra realmente.
- **Inyección de HTML en el panel Changes**: los nombres de archivo que se muestran en el panel Agent Changes (etiqueta de la lista y tooltip) ahora se escapan antes de renderizarse como HTML. Así, un archivo creado o renombrado con caracteres como `<`, `>` o `&` — incluso por un agente de IA que actúe en el proyecto — ya no puede inyectar marcado ni provocar contenido no deseado en el panel.

## [1.3.0] — 2026-08-18

### Añadido

- **Integración con Codex**: Prism ahora admite sesiones de Claude Code y de la CLI de OpenAI Codex desde la misma ventana de herramientas.
- **Selector de nueva sesión**: cuando están instaladas las dos CLIs compatibles, la acción New Session permite elegir entre iniciar una sesión de Claude Code o de Codex.
- **Configuración del agente predeterminado**: la configuración ahora incluye una CLI predeterminada y rutas de ejecutables separadas para Claude Code y Codex.
- **Historial de conversaciones de Codex**: el panel History permite explorar las sesiones de Codex en `~/.codex/sessions`, filtradas por `cwd` para el proyecto actual del IDE.
- **Barra de herramientas completa para Codex**: los botones Resume, Compact, Clear, Model, Effort y Cost ahora funcionan en las sesiones de Codex, vinculados a sus equivalentes en Codex. Resume/Compact/Clear envían el mismo comando de barra; Model y Effort controlan el selector interactivo `/model` de Codex (lista de modelos y nivel de razonamiento) mediante pulsaciones de teclas; Cost es un menú desplegable que abre la vista `/usage` de actividad de tokens de Codex para el período diario, semanal o acumulado.

### Cambiado

- **Panel Agent Changes**: la ventana de cambios ahora funciona independientemente del agente, por lo que las sesiones de Codex usan las mismas instantáneas por interacción, navegación de diff y flujo de reversión que las sesiones de Claude.
- **Terminología de agentes**: las acciones, la configuración, el estado y la documentación que ve el usuario ahora se refieren a Prism o al agente activo cuando el comportamiento se aplica tanto a Claude Code como a Codex.
- **Metadatos del plugin**: el nombre del plugin, la descripción en Marketplace, el README y los mensajes traducidos ahora describen la compatibilidad con Claude Code y Codex.
- **Campo de patrones excluidos**: la configuración ahora usa un área de texto de varias líneas (un patrón por línea) en lugar de un campo de una sola línea, con un contador actualizado de los patrones definidos; los valores separados por comas siguen siendo compatibles para mantener la compatibilidad con versiones anteriores.

### Corregido

- **Seguridad de los enlaces simbólicos en instantáneas y reversiones**: las instantáneas y las operaciones de reversión ahora omiten cualquier ruta que atraviese un enlace simbólico, impidiendo que el diff de una interacción lea o sobrescriba archivos fuera del directorio del proyecto a través de una carpeta con un enlace simbólico.

## [1.2.2] — 2026-06-30

### Cambiado

- **Ctrl+V en Linux**: ahora pega el contenido del portapapeles. Si contiene una imagen, sus bytes se escriben en un PNG temporal y la ruta del archivo se pega en el prompt (Claude adjunta el archivo); de lo contrario, el texto del portapapeles se pega usando secuencias de pegado delimitado para evitar el envío automático del contenido de varias líneas. Fuerza el pegado como texto sin formato con `Ctrl+Shift+V`. macOS y Windows mantienen el pegado nativo con `Ctrl+V`.

## [1.2.1] — 2026-06-15

### Añadido

- **Exclusiones de instantáneas con comodines**: los patrones de exclusión ahora admiten `*`, `?` y `**` (por ejemplo, `cmake-build-*`, `**/generated`)

### Cambiado

- **Coincidencia de exclusiones**: los patrones exactos (`build`, `target`, etc.) ahora coinciden con cualquier segmento de la ruta, por lo que también excluyen directorios anidados como `src/build/`

### Corregido

- **Bloqueo de la EDT**: el cálculo de diff ahora se ejecuta en un hilo en segundo plano, eliminando los bloqueos del IDE en proyectos con muchos archivos bajo seguimiento (corrige #9)

## [1.2.0] — 2026-04-17

### Añadido

- **Botón Clear**: nuevo botón de la barra de herramientas que envía `/clear` con una ventana de confirmación (mismo patrón de experiencia de usuario que Compact)
- **Effort: nivel xhigh**: añadido el nivel de esfuerzo `xhigh` entre `high` y `max` en el menú desplegable de esfuerzo
- **Selector de esfuerzo**: opción "Open effort picker..." en el menú desplegable de esfuerzo — envía `/effort` para abrir el control deslizante interactivo nativo de Claude en el terminal
- **Selector de modelo**: opción "Open model picker..." en el menú desplegable de modelos — envía `/model` para abrir el selector interactivo nativo de modelos de Claude en el terminal
- **Mention in Claude**: nueva acción "Mention in Claude" en el menú contextual del explorador de proyectos — inserta `@relative/path` en la posición del cursor del terminal para cualquier archivo o carpeta

### Corregido

- **Atajo Send Selection**: la referencia a la selección (`@file:line`) ahora se inserta con un espacio al final en lugar de un salto de línea

## [1.1.2] — 2026-03-31

### Corregido

- **Conformidad con la API**: se sustituyeron 8 usos de la API interna `ActionToolbarImpl` por la API pública `ActionManager.createActionToolbar()` en ClaudeToolbar, DiffPanel y HistoryPanel
- **API obsoleta**: se sustituyó `FileChooserDescriptorFactory.createSingleFileDescriptor()` por el constructor de `FileChooserDescriptor` en la configuración

## [1.1.1] — 2026-03-30

### Corregido

- **DiffPanel**: se resolvió el error `Write-unsafe context` al actualizar el VFS durante la selección de pestañas — `VirtualFile.refresh()` pasó a ejecutarse dentro de `invokeLater` para garantizar un contexto seguro para escritura

### Cambiado

- **Descripción**: se reescribió la descripción del plugin para Marketplace con un aviso de responsabilidad, información sobre la licencia Apache 2.0 y enlaces para contribuir
- **Icono**: se añadió `pluginIcon_dark.svg` para mejorar la visibilidad en temas oscuros
- **Metadatos**: se eliminó la `<version>` fija de plugin.xml (ahora se obtiene exclusivamente de gradle.properties) y se actualizó el correo electrónico del proveedor

## [1.1.0] — 2026-03-27

### Cambiado

- **Compatibilidad**: se eliminó el límite superior de build del IDE (`untilBuild`) — el plugin ahora funciona con IntelliJ 2024.3 y todas las versiones futuras (corrige el error de instalación en 2026.1+)
- **Dependencias**: se actualizaron IntelliJ Platform Gradle Plugin (2.2.1 → 2.11.0), JUnit Jupiter (5.10.2 → 5.11.4) y Gradle wrapper (8.10.2 → 8.13)

### Corregido

- **Acciones**: se añadió la sobrescritura explícita de `ActionUpdateThread.BGT` a `AskClaudeAction`, `SendSelectionAction`, `ShowDiffAction`, `InsertFileReferenceAction` y `OpenClaudeAction` (buena práctica para IntelliJ 241+, elimina avisos de obsolescencia en builds más recientes)

## [1.0.1] — 2026-03-26

### Corregido

- **Historial**: se corrigió el escape de rutas de proyectos en directorios con guiones bajos (por ejemplo, `my_cool-project`). Claude Code sustituye tanto `/` como `_` por `-`, pero el plugin solo sustituía `/`. Se añadió una resolución con múltiples estrategias y coincidencia aproximada como alternativa.

### Cambiado

- **Icono**: nuevo icono minimalista con contorno de rombo que sustituye a la antigua insignia con la letra "C".

## [1.0.0] — 2026-03-26

### Añadido

**Terminal y gestión de procesos**
- Terminal interactivo con la CLI de Claude Code integrada en el IDE
- Compatibilidad completa con colores ANSI y formato de texto
- PTY real (pty4j + JediTerm) para máxima compatibilidad
- Múltiples sesiones: varias sesiones independientes en pestañas simultáneas
- Inicio automático de Claude al abrir un proyecto (configurable)

**Vista de diff y seguimiento de cambios**
- Panel Claude Changes: visualiza los archivos modificados por interacción
- Diff nativo del IDE lado a lado (original vs. modificado)
- Instantáneas incrementales en disco (sin consumo adicional de RAM para repositorios grandes)
- Reversión por archivo o por interacción completa
- Navegación entre interacciones del historial (anterior / siguiente)
- Actualización automática cuando Claude termina
- Botón "Clear Interactions" con confirmación y sincronización entre paneles

**Integración con el IDE y contexto**
- Send Selection: envía el texto seleccionado a Claude
- Insert File Reference: inserta @path en el terminal
- Acciones del menú contextual: Explain / Review / Fix / Generate Tests / Refactor
- Captura automática de contexto (archivo activo, selección, archivos abiertos)
- Atajos de teclado personalizables (Cmd/Alt en macOS, Ctrl/Alt en Linux)

**Barra de herramientas y productividad**
- Barra de herramientas compacta con botones de acciones rápidas
- Menús desplegables: Model (opus/sonnet/haiku), Effort (auto/low/medium/high/max), Cost
- Botones: Compact, Resume, Templates
- Plantillas de prompts: reutilizables con variables {selection}, {file}, {language}

**Ajustes y configuración**
- Apariencia: controla la visualización del panel Changes al inicio y del widget de la barra de estado
- Instantánea: patrones de exclusión y tamaño máximo de archivo
- Ruta de Claude, shell e inicio automático configurables
- Idioma: inglés, portugués y español (seleccionable en la configuración)

**Historial y sesiones**
- Explorador del historial de conversaciones: navega por conversaciones anteriores
- Búsqueda de texto completo en el historial
- Compatibilidad con varias sesiones paralelas con estado independiente
- Vista del historial con formato nativo del IDE

**Visibilidad del estado**
- Widget de la barra de estado: muestra el estado de Claude (trabajando / inactivo / detenido)
- Modelo y esfuerzo visibles en tiempo real
- Haz clic en el widget para abrir el panel de Claude
- Compatibilidad con múltiples sesiones: [2/4 trabajando]

### Técnico

- **Lenguaje**: Kotlin + Gradle Kotlin DSL
- **Plataforma**: IntelliJ Platform Plugin 2.x, IDE 2024.3+
- **Runtime**: JDK 17+
- **Pruebas**: más de 34 pruebas unitarias que cubren los servicios principales
- **CI/CD**: GitHub Actions preparado para build, pruebas, verificación y release
