# <img src="src/main/resources/icons/prism.svg" width="24" height="24" /> Prism — IDE Companion for Claude Code

[![Version](https://img.shields.io/badge/version-1.2.1-blue.svg)](https://github.com/VGirotto/prism-claude-code-plugin/releases)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![JetBrains](https://img.shields.io/badge/JetBrains-2024.3+-orange.svg)](https://plugins.jetbrains.com/)

> [Read in English](README.md)

Plugin completo para JetBrains que integra o **Claude Code CLI** diretamente na sua IDE — com interface gráfica, diff view por interação, histórico de conversas e suporte a múltiplas sessões.

Prism é um **wrapper visual local** — ele executa o Claude Code CLI via PTY real e **não faz chamadas externas**. Você precisa ter o CLI instalado e autenticado de forma independente.

<img src="docs/images/prism.gif" width="80%" />

> **Aviso:** Este é um plugin da comunidade, não afiliado ou endossado pela Anthropic, PBC. "Claude" e "Claude Code" são marcas da Anthropic, PBC.

---

## 🚀 Instalação Rápida

> **3 passos para começar — sem precisar compilar!**

### Pré-requisitos

| Requisito | Versão | Notas |
|-----------|--------|-------|
| 🖥️ **IDE JetBrains** | 2024.3+ | IntelliJ IDEA, GoLand, WebStorm, PyCharm, CLion |
| 🤖 **Claude Code CLI** | 1.0+ | `npm install -g @anthropic-ai/claude-code` |

### Opção 1: Download do Release (Recomendado) ⭐

1. 📦 Baixe o `.zip` mais recente em [**Releases**](https://github.com/VGirotto/prism-claude-code-plugin/releases)
2. ⚙️ Na IDE: **Settings → Plugins → ⚙️ Engrenagem → Install Plugin from Disk**
3. 🔄 **Reinicie** a IDE — o painel "Claude Code" aparece na barra inferior

Pronto! 🎉

### Opção 2: Compilar Localmente 🔧

<details>
<summary>Clique para expandir as instruções de build</summary>

```bash
git clone https://github.com/VGirotto/prism-claude-code-plugin.git
cd prism-claude-code-plugin

# Defina JAVA_HOME se não tiver JDK global (17+)
export JAVA_HOME="/caminho/para/sua/IDE.app/Contents/jbr/Contents/Home"

./gradlew buildPlugin

# Instalar: Settings > Plugins > Install Plugin from Disk
# Selecione: build/distributions/*.zip
```

</details>

---

## 🎬 Features em Ação

### 🖥️ Terminal Interativo

Terminal completo com Claude Code rodando dentro da IDE com suporte a cores ANSI e PTY real (pty4j + JediTerm).

Toolbar compacta com ações rápidas: **Model** (opus/sonnet/haiku), **Effort** (auto/low/medium/high/max), **Cost**, **Resume** e mais.

<img src="docs/images/commands.gif" width="80%" />

---

### 📝 Claude Changes Panel

Diff view automático de todos os arquivos modificados por interação — diff side-by-side nativo da IDE com navegação entre interações.

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

### 📋 Prompt Templates & Multi-Session

[Prompt Templates](docs/prompt-templates.md) reutilizáveis com variáveis `{selection}`, `{file}`, `{language}`. Execute múltiplas sessões simultâneas em tabs independentes.

<img src="docs/images/template-multisession.gif" width="80%" />

---

### 🕐 Histórico de Conversas

Navegue por conversas anteriores com busca full-text. Retome qualquer sessão anterior.

<img src="docs/images/history.gif" width="80%" />

---

### ⚙️ Configurações

Configure o caminho do Claude, shell, idioma, exclusões, auto-start e toggles.
Exclusões de snapshot aceitam nomes ou padrões curinga separados por vírgula, por exemplo `node_modules`, `cmake-build-*` ou `**/generated`.

<img src="docs/images/settings.png" width="60%" />

---

## ⌨️ Atalhos de Teclado

| Atalho | Ação | Plataforma |
|--------|------|------------|
| `Cmd+Shift+C` | Abrir/fechar Claude Code | macOS |
| `Alt+Shift+C` | Abrir/fechar Claude Code | Linux/Windows |
| `Ctrl+Shift+D` | Mostrar Claude Changes (diff) | macOS |
| `Ctrl+Alt+Shift+D` | Mostrar Claude Changes (diff) | Linux/Windows |
| `Ctrl+Shift+Enter` | Enviar seleção ao Claude | macOS |
| `Ctrl+Alt+Shift+Enter` | Enviar seleção ao Claude | Linux/Windows |
| `Ctrl+Shift+K` | Inserir referência @arquivo | macOS |
| `Ctrl+Alt+Shift+K` | Inserir referência @arquivo | Linux/Windows |

> No macOS, `Ctrl` = tecla Control física (não Cmd).

### 🔗 Acessos Rápidos

- **Menu IDE**: `Tools > Toggle Claude Code`
- **Configurações**: `Settings > Tools > Prism — Claude Code`
- **Status Bar**: Clique no widget para abrir o painel Claude

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
