# Histórico de alterações

Todas as mudanças relevantes deste projeto serão documentadas neste arquivo.

O formato é baseado no [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
e este projeto segue o [Versionamento Semântico](https://semver.org/spec/v2.0.0.html).

## [1.4.0] — 2026-10-03

### Adicionado

- **Sessões de agentes em painéis divididos**: mova as abas de sessão do Prism para divisões à direita ou na parte inferior da janela de ferramentas, reúna-as com Unsplit ou crie uma nova sessão independente diretamente em uma divisão.
- **Fonte e configurações do terminal**: o Prism agora segue as configurações de fonte, espaçamento entre linhas, zoom e demais preferências do terminal da IDE.
- **Entrada Font Settings no menu**: abra as configurações do terminal da IDE pelo item `Font Settings` no menu de opções (⋮). As configurações dedicadas de fonte e esse item do menu exigem o build **251.25410+** (IntelliJ IDEA **2025.1.1+**). A engrenagem da barra abre as configurações do Prism.
- **Visualizador de changelog**: um botão ao lado das configurações do Prism abre o histórico completo de alterações com formatação Markdown, em inglês, português brasileiro ou espanhol, de acordo com o idioma da interface do Prism. Os changelogs estão incluídos no plugin e disponíveis offline.

### Alterado

- **Controles vinculados à sessão**: os atalhos do terminal e as ações da barra de ferramentas agora permanecem vinculados à sessão a que pertencem quando vários painéis estão visíveis; as ações do editor continuam direcionadas à última sessão do Prism que recebeu foco.
- **Linha do tempo de Diff para todo o projeto**: sessões sequenciais mantêm interações numeradas independentes, enquanto sessões simultâneas compartilham uma única base de comparação e produzem uma entrada `Multiple chats` depois que todos os participantes ficam ociosos.

### Corrigido

- **Teclas do terminal**: ESC, Shift+Enter, Ctrl+V (incluindo a colagem de imagens no Linux) e os atalhos do agente funcionam com `Override IDE shortcuts` ativado ou desativado.
- **Ciclo de vida da inicialização**: fechar uma sessão enquanto seu PTY está iniciando não permite mais anexar um terminal após o descarte da sessão nem deixar um processo órfão.
- **Coordenação dos snapshots de Diff**: abrir outra sessão, receber saída durante a inicialização, selecionar abas e atualizar manualmente não reinicia mais o trabalho pendente nem adiciona números de interação duplicados.

## [1.3.1] — 2026-08-26

### Corrigido

- **Argumentos personalizados da CLI**: as configurações dos comandos do Claude Code e do Codex agora aceitam argumentos opcionais, incluindo caminhos entre aspas com espaços. O Prism valida o executável separadamente e preserva cada argumento ao iniciar a sessão (corrige #13).
- **Reordenação de abas de conversa**: arrastar uma aba para uma nova posição não a congela mais. A plataforma reordena uma aba removendo seu conteúdo e adicionando-o novamente no novo índice, o que o Prism interpretava como o fechamento da aba e usava como sinal para encerrar o processo do agente daquela sessão — a aba reaparecia com o terminal ainda desenhado, mas sem nada em execução por trás. O encerramento da sessão agora está vinculado ao descarte efetivo da aba, que só ocorre quando ela é realmente fechada.
- **Injeção de HTML no painel Changes**: os nomes de arquivos exibidos no painel Agent Changes (rótulo da lista e tooltip) agora são escapados antes da renderização como HTML. Assim, um arquivo criado ou renomeado com caracteres como `<`, `>` ou `&` — inclusive por um agente de IA atuando no projeto — não pode mais injetar marcação nem provocar conteúdo não intencional no painel.

## [1.3.0] — 2026-08-18

### Adicionado

- **Integração com Codex**: o Prism agora oferece suporte a sessões do Claude Code e da CLI do OpenAI Codex na mesma janela de ferramentas.
- **Seletor de nova sessão**: quando as duas CLIs compatíveis estão instaladas, a ação New Session permite escolher entre iniciar uma sessão do Claude Code ou do Codex.
- **Configuração do agente padrão**: as configurações agora incluem uma CLI padrão e caminhos de executáveis separados para Claude Code e Codex.
- **Histórico de conversas do Codex**: o painel History permite navegar pelas sessões do Codex em `~/.codex/sessions`, filtradas pelo `cwd` para o projeto atual da IDE.
- **Barra de ferramentas completa para Codex**: os botões Resume, Compact, Clear, Model, Effort e Cost agora funcionam nas sessões do Codex, mapeados para seus equivalentes no Codex. Resume/Compact/Clear enviam o mesmo comando de barra; Model e Effort controlam o seletor interativo `/model` do Codex (lista de modelos e nível de raciocínio) por meio de teclas; Cost é um menu suspenso que abre a visualização `/usage` de atividade de tokens do Codex para o período diário, semanal ou acumulado.

### Alterado

- **Painel Agent Changes**: a janela de alterações agora funciona independentemente do agente, permitindo que sessões do Codex usem os mesmos snapshots por interação, navegação de diff e fluxo de reversão das sessões do Claude.
- **Terminologia dos agentes**: ações, configurações, status e documentação apresentados ao usuário agora se referem ao Prism ou ao agente ativo quando o comportamento se aplica tanto ao Claude Code quanto ao Codex.
- **Metadados do plugin**: o nome do plugin, a descrição no Marketplace, o README e as mensagens traduzidas agora descrevem o suporte a Claude Code e Codex.
- **Campo de padrões excluídos**: as configurações agora usam uma área de texto com várias linhas (um padrão por linha) em vez de um campo de linha única, com uma contagem atualizada dos padrões definidos; valores separados por vírgula continuam sendo aceitos para compatibilidade com versões anteriores.

### Corrigido

- **Segurança de links simbólicos em snapshots e reversões**: snapshots e operações de reversão agora ignoram qualquer caminho que atravesse um link simbólico, impedindo que o diff de uma interação leia ou sobrescreva arquivos fora do diretório do projeto por meio de uma pasta com link simbólico.

## [1.2.2] — 2026-06-30

### Alterado

- **Ctrl+V no Linux**: agora cola o conteúdo da área de transferência. Se houver uma imagem, seus bytes são gravados em um PNG temporário e o caminho do arquivo é colado no prompt (o Claude anexa o arquivo); caso contrário, o texto da área de transferência é colado usando sequências de colagem delimitada para evitar o envio automático de conteúdo com várias linhas. Force a colagem como texto simples com `Ctrl+Shift+V`. macOS e Windows mantêm a colagem nativa com `Ctrl+V`.

## [1.2.1] — 2026-06-15

### Adicionado

- **Exclusões de snapshots com curingas**: os padrões de exclusão agora aceitam `*`, `?` e `**` (por exemplo, `cmake-build-*`, `**/generated`)

### Alterado

- **Correspondência de exclusões**: padrões exatos (`build`, `target` etc.) agora correspondem a qualquer segmento do caminho, excluindo também diretórios aninhados como `src/build/`

### Corrigido

- **Travamento da EDT**: o cálculo de diff agora é executado em uma thread em segundo plano, eliminando travamentos da IDE em projetos com muitos arquivos rastreados (corrige #9)

## [1.2.0] — 2026-04-17

### Adicionado

- **Botão Clear**: novo botão na barra de ferramentas que envia `/clear` com uma janela de confirmação (mesmo padrão de experiência do usuário de Compact)
- **Effort: nível xhigh**: adicionado o nível de esforço `xhigh` entre `high` e `max` no menu suspenso de esforço
- **Seletor de esforço**: opção "Open effort picker..." no menu suspenso de esforço — envia `/effort` para abrir o controle deslizante interativo nativo do Claude no terminal
- **Seletor de modelo**: opção "Open model picker..." no menu suspenso de modelos — envia `/model` para abrir o seletor interativo nativo de modelos do Claude no terminal
- **Mention in Claude**: nova ação "Mention in Claude" no menu de contexto do explorador de projetos — insere `@relative/path` na posição do cursor do terminal para qualquer arquivo ou pasta

### Corrigido

- **Atalho Send Selection**: a referência à seleção (`@file:line`) agora é inserida com um espaço ao final em vez de uma quebra de linha

## [1.1.2] — 2026-03-31

### Corrigido

- **Conformidade com a API**: substituídos 8 usos da API interna `ActionToolbarImpl` pela API pública `ActionManager.createActionToolbar()` em ClaudeToolbar, DiffPanel e HistoryPanel
- **API obsoleta**: substituído `FileChooserDescriptorFactory.createSingleFileDescriptor()` pelo construtor de `FileChooserDescriptor` nas configurações

## [1.1.1] — 2026-03-30

### Corrigido

- **DiffPanel**: resolvido o erro `Write-unsafe context` ao atualizar o VFS durante a seleção de abas — `VirtualFile.refresh()` passou a ser executado dentro de `invokeLater` para garantir um contexto seguro para escrita

### Alterado

- **Descrição**: reescrita a descrição do plugin para o Marketplace com aviso de responsabilidade, informação sobre a licença Apache 2.0 e links para contribuição
- **Ícone**: adicionado `pluginIcon_dark.svg` para melhorar a visibilidade em temas escuros
- **Metadados**: removida a `<version>` fixa de plugin.xml (agora obtida exclusivamente de gradle.properties) e atualizado o e-mail do fornecedor

## [1.1.0] — 2026-03-27

### Alterado

- **Compatibilidade**: removido o limite superior de build da IDE (`untilBuild`) — o plugin agora funciona com IntelliJ 2024.3 e todas as versões futuras (corrige o erro de instalação no 2026.1+)
- **Dependências**: atualizados IntelliJ Platform Gradle Plugin (2.2.1 → 2.11.0), JUnit Jupiter (5.10.2 → 5.11.4) e Gradle wrapper (8.10.2 → 8.13)

### Corrigido

- **Ações**: adicionada a sobrescrita explícita de `ActionUpdateThread.BGT` a `AskClaudeAction`, `SendSelectionAction`, `ShowDiffAction`, `InsertFileReferenceAction` e `OpenClaudeAction` (boa prática para IntelliJ 241+, elimina avisos de obsolescência em builds mais recentes)

## [1.0.1] — 2026-03-26

### Corrigido

- **Histórico**: corrigido o escape de caminhos de projetos em diretórios com sublinhados (por exemplo, `my_cool-project`). O Claude Code substitui tanto `/` quanto `_` por `-`, mas o plugin substituía apenas `/`. Adicionada a resolução por múltiplas estratégias, com correspondência aproximada como alternativa.

### Alterado

- **Ícone**: novo ícone minimalista com contorno de losango substituindo o antigo emblema com a letra "C".

## [1.0.0] — 2026-03-26

### Adicionado

**Terminal e gerenciamento de processos**
- Terminal interativo com a CLI do Claude Code integrada à IDE
- Suporte completo a cores ANSI e formatação de texto
- PTY real (pty4j + JediTerm) para máxima compatibilidade
- Múltiplas sessões: várias sessões independentes em abas simultâneas
- Inicialização automática do Claude ao abrir um projeto (configurável)

**Visualização de diff e acompanhamento de alterações**
- Painel Claude Changes: visualize os arquivos modificados por interação
- Diff nativo da IDE lado a lado (original vs. modificado)
- Snapshots incrementais em disco (sem consumo adicional de RAM para grandes repositórios)
- Reversão por arquivo ou por interação completa
- Navegação entre interações do histórico (anterior / próxima)
- Atualização automática após o Claude terminar
- Botão "Clear Interactions" com confirmação e sincronização entre painéis

**Integração com a IDE e contexto**
- Send Selection: envie o texto selecionado para o Claude
- Insert File Reference: insira @path no terminal
- Ações do menu de contexto: Explain / Review / Fix / Generate Tests / Refactor
- Captura automática de contexto (arquivo ativo, seleção, arquivos abertos)
- Atalhos de teclado personalizáveis (Cmd/Alt no macOS, Ctrl/Alt no Linux)

**Barra de ferramentas e produtividade**
- Barra de ferramentas compacta com botões de ações rápidas
- Menus suspensos: Model (opus/sonnet/haiku), Effort (auto/low/medium/high/max), Cost
- Botões: Compact, Resume, Templates
- Templates de prompts: reutilizáveis com variáveis {selection}, {file}, {language}

**Ajustes e configuração**
- Aparência: controle a exibição do painel Changes na inicialização e do widget da barra de status
- Snapshot: padrões de exclusão e tamanho máximo de arquivo
- Caminho do Claude, shell e inicialização automática configuráveis
- Idioma: inglês, português e espanhol (selecionável nas configurações)

**Histórico e sessões**
- Navegador do histórico de conversas: navegue por conversas anteriores
- Pesquisa de texto completo no histórico
- Suporte a várias sessões paralelas com estado independente
- Visualização do histórico com formatação nativa da IDE

**Visibilidade do status**
- Widget da barra de status: mostra o estado do Claude (trabalhando / ocioso / parado)
- Modelo e esforço visíveis em tempo real
- Clique no widget para abrir o painel do Claude
- Suporte a múltiplas sessões: [2/4 trabalhando]

### Técnico

- **Linguagem**: Kotlin + Gradle Kotlin DSL
- **Plataforma**: IntelliJ Platform Plugin 2.x, IDE 2024.3+
- **Runtime**: JDK 17+
- **Testes**: mais de 34 testes unitários cobrindo os principais serviços
- **CI/CD**: GitHub Actions preparado para build, testes, verificação e release
