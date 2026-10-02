# <img src="src/main/resources/icons/prism.svg" width="24" height="24" /> Prism — IDE Companion for Claude Code and Codex

[![Version](https://img.shields.io/badge/version-1.4.0-blue.svg)](https://github.com/VGirotto/prism-claude-code-plugin/releases)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![JetBrains](https://img.shields.io/badge/JetBrains-2024.3+-orange.svg)](https://plugins.jetbrains.com/plugin/31025-prism--ide-companion-for-claude-code-and-codex)

> [Read in English](README.md)

Plugin completo para JetBrains que integra o **Claude Code CLI** e o **OpenAI Codex CLI** diretamente na sua IDE — com interface gráfica, diff view por interação, histórico de conversas e múltiplas sessões em tabs ou painéis divididos.

Prism é um **wrapper visual local** — ele executa cada CLI via PTY real e **não faz chamadas externas**. Você precisa ter o(s) CLI(s) instalado(s) e autenticado(s) de forma independente.

<img src="docs/images/prism.gif" width="80%" />

> **Aviso:** Este é um plugin da comunidade, não afiliado ou endossado pela Anthropic, PBC ou OpenAI. "Claude" e "Claude Code" são marcas da Anthropic, PBC. "Codex" é marca da OpenAI.

---

## 🚀 Instalação Rápida

> **3 passos para começar — sem precisar compilar!**

### Pré-requisitos

| Requisito | Versão | Notas |
|-----------|--------|-------|
| 🖥️ **IDE JetBrains** | 2024.3+ | IntelliJ IDEA, GoLand, WebStorm, PyCharm, CLion |
| 🤖 **Claude Code CLI** | 1.0+ | `npm install -g @anthropic-ai/claude-code` (opcional, se você só usa Codex) |
| 🧠 **Codex CLI** | 0.10+ | `npm install -g @openai/codex` (opcional, se você só usa Claude) |

Pelo menos um dos CLIs é necessário. O botão "Nova Sessão" exibe um seletor quando ambos estão instalados.

### Opção 1: JetBrains Marketplace (Recomendado) ⭐

Instale diretamente pelo [**JetBrains Marketplace**](https://plugins.jetbrains.com/plugin/31025-prism--ide-companion-for-claude-code-and-codex), o catálogo oficial de plugins da JetBrains — rápido e fácil.

1. ⚙️ Na IDE, abra **Settings → Plugins → Marketplace**
2. 🔎 Busque **Prism - IDE Companion for Claude Code and Codex** e clique em **Install**
3. 🚀 Reinicie a IDE se solicitado e abra **View → Tool Windows → Prism**

Pronto! 🎉 Você pode gerenciar futuras atualizações em **Settings → Plugins → Installed**.

<img src="docs/images/marketplace.png" alt="Prism listado no JetBrains Marketplace" width="60%" />

### Opção 2: Download pelo GitHub Releases 📦

1. 📦 Baixe o `.zip` mais recente em [**Releases**](https://github.com/VGirotto/prism-claude-code-plugin/releases)
2. ⚙️ Na IDE: **Settings → Plugins → ⚙️ Engrenagem → Install Plugin from Disk**
3. 🚀 Reinicie a IDE se solicitado e abra **View → Tool Windows → Prism**

### Opção 3: Compilar Localmente 🔧

<details>
<summary>Clique para expandir as instruções de build</summary>

```bash
git clone https://github.com/VGirotto/prism-claude-code-plugin.git
cd prism-claude-code-plugin

# Defina JAVA_HOME para uma instalação do JDK 21
export JAVA_HOME="/caminho/para/jdk-21"

./gradlew buildPlugin

# Instalar: Settings > Plugins > Install Plugin from Disk
# Selecione: build/distributions/*.zip
```

</details>

---

## 🎬 Features em Ação

### 🖥️ Terminal Interativo

Terminal completo do agente rodando dentro da IDE com suporte a cores ANSI e PTY real (pty4j + JediTerm). Escolha Claude Code ou Codex ao iniciar uma nova sessão.

Toolbar compacta com ações rápidas: **Resume**, **Compact**, **Clear**, **Model**, **Effort**, **Cost**, **Templates** e **Settings**. Todos os botões funcionam tanto em sessões Claude Code quanto Codex, mapeados para os comandos de cada agente (por exemplo, **Cost** executa `/cost` no Claude e abre as visões de atividade de tokens do `/usage` no Codex).

<img src="docs/images/commands.gif" width="80%" />

---

### 🎨 Aparência do Terminal e Configurações de Fonte

O Prism usa as configurações do terminal da IDE, permitindo personalizar sua aparência:

- **Fonte, tamanho e espaçamento entre linhas**: abra **Settings → Tools → Terminal → Font Settings**. Nas IDEs 2025.1.1+, o menu de opções do Prism (**⋮ → Configurações de Fonte…**) leva você diretamente a essa página. As mudanças se aplicam aos terminais abertos do Prism, e **Ctrl+roda do mouse** ajusta o tamanho da fonte.
- **Cores**: ajuste a paleta do terminal em **Settings → Editor → Color Scheme → Console Colors**. O tema escolhido no próprio CLI do agente também influencia a aparência da saída.

Nas IDEs anteriores, as fontes ficam em **Settings → Editor → Color Scheme → Console Font**. A engrenagem na toolbar do agente abre as **configurações do Prism**; a opção **Configurações de Fonte** fica no menu de opções da tool window.

<img src="docs/images/font-settings.png" alt="Terminal do Prism com a opção Font Settings no menu de opções da tool window" width="80%" />

---

### 📝 Painel de Mudanças do Agente

Diff view automático de todos os arquivos modificados por interação — diff side-by-side nativo da IDE com navegação entre interações. Funciona tanto para sessões Claude quanto Codex.

<img src="docs/images/changes.gif" width="80%" />

Navegue pelo histórico de interações:

<img src="docs/images/interactions.gif" width="80%" />

Reverta por arquivo ou por interação completa com um clique:

<img src="docs/images/revert.gif" width="80%" />

---

### 🖱️ Menu de Contexto & Integração com a IDE

Clique direito no editor para acessar: **Explain** / **Review** / **Fix** / **Generate Tests** / **Refactor**.

<img src="docs/images/context-menu.png" width="60%" />

- 📎 Referência de arquivo com `@path` no terminal
- 🎯 Auto-capture de contexto (arquivo ativo, seleção, arquivos abertos)

---

### 🪟 Dividir Sessões (Split Sessions)

Mantenha sessões do Claude Code e do Codex visíveis ao mesmo tempo, lado a lado ou empilhadas. Abra **Dividir Sessões** na barra de título do Prism para:

- Mover a sessão selecionada para uma divisão **à direita** ou **abaixo**, quando houver várias tabs abertas no painel de origem.
- Criar uma **nova sessão independente** diretamente em uma divisão à direita ou abaixo, escolhendo Claude Code ou Codex.
- Usar **Remover Divisão de Sessões** para reunir os painéis novamente.

Cada sessão mantém seu próprio terminal e toolbar. As ações do editor usam a última sessão do Prism que recebeu foco. O menu de divisão aparece quando a IDE suporta divisão de tabs de tool windows.

<img src="docs/images/split.png" alt="Sessões do Claude Code e do Codex lado a lado com o menu Split Sessions aberto" width="80%" />

---

### 📋 Prompt Templates & Multi-Session

[Prompt Templates](docs/prompt-templates.md) reutilizáveis com variáveis `{selection}`, `{file}`, `{language}`. Mantenha múltiplas sessões independentes em tabs ou use Dividir Sessões para visualizá-las juntas.

<img src="docs/images/template-multisession.gif" width="80%" />

---

### 🕐 Histórico de Conversas

Navegue por conversas anteriores com busca full-text. Retome qualquer sessão anterior. O histórico filtra pelo CLI da sessão ativa:

- Sessões **Claude** são lidas de `~/.claude/projects/<caminho-do-projeto-escapado>/*.jsonl`
- Sessões **Codex** são lidas de `~/.codex/sessions/AAAA/MM/DD/rollout-*.jsonl` e filtradas pelo `cwd` da sessão

<img src="docs/images/history.gif" width="80%" />

---

### ⚙️ Configurações

Configure shell, **agente padrão**, **caminho do Claude**, **caminho do Codex**, idioma, exclusões, auto-start e toggles.
Exclusões de snapshot aceitam nomes ou padrões curinga separados por vírgula, por exemplo `node_modules`, `cmake-build-*` ou `**/generated`.

<img src="docs/images/settings.png" width="60%" />

---

## ⌨️ Atalhos de Teclado

| Atalho | Ação | Plataforma |
|--------|------|------------|
| `Cmd+Shift+C` | Abrir/fechar Prism | macOS |
| `Alt+Shift+C` | Abrir/fechar Prism | Linux/Windows |
| `Ctrl+Shift+D` | Mostrar Mudanças do Agente (diff) | macOS |
| `Ctrl+Alt+Shift+D` | Mostrar Mudanças do Agente (diff) | Linux/Windows |
| `Ctrl+Shift+Enter` | Enviar seleção ao agente | macOS |
| `Ctrl+Alt+Shift+Enter` | Enviar seleção ao agente | Linux/Windows |
| `Ctrl+Shift+K` | Inserir referência @arquivo | macOS |
| `Ctrl+Alt+Shift+K` | Inserir referência @arquivo | Linux/Windows |

> No macOS, `Ctrl` = tecla Control física (não Cmd).

#### Colar no terminal do agente (Linux)

No Linux, `Ctrl+V` inspeciona a área de transferência: se contiver uma imagem, os bytes são gravados em um PNG temporário e o caminho do arquivo é colado no prompt (o agente anexa o arquivo). Caso contrário, o texto da área de transferência é colado (envolto em escapes de bracketed paste, então conteúdo de múltiplas linhas não é submetido automaticamente). Use `Ctrl+Shift+V` para forçar uma colagem de texto puro. No macOS e Windows, `Ctrl+V` mantém o comportamento nativo do CLI do agente.

### 🔗 Acessos Rápidos

- **Menu IDE**: `Tools > Toggle Prism`
- **Configurações**: `Settings > Tools > Prism`
- **Status Bar**: Clique no widget para abrir o painel do agente

---

## 🤝 Contribuindo

Veja [CONTRIBUTING.md](CONTRIBUTING.md) para setup de desenvolvimento, comandos de build e workflow de contribuição.

Encontrou um bug ou tem uma ideia? Abra uma [Issue](https://github.com/VGirotto/prism-claude-code-plugin/issues) 🐛

---

## 📚 Documentação

- [Guia de Prompt Templates](docs/prompt-templates.md)
- [Arquitetura & Estrutura do Projeto](docs/architecture.md)

---

## 📄 Licença

Apache License 2.0 — veja [LICENSE](LICENSE) para detalhes.
