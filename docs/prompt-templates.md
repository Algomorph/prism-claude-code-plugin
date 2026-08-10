# Prompt Templates

Prompt Templates let you define reusable prompts with placeholder variables that are automatically substituted with context from your editor before being sent to the agent.

---

## What Are Prompt Templates

A Prompt Template is a text prompt that contains one or more variables enclosed in curly braces (e.g., `{selection}`, `{file}`, `{language}`). When you apply a template, Prism reads the current editor state and replaces each variable with the corresponding value before sending the prompt to the agent.

This eliminates repetitive typing and ensures your prompts are always grounded in the actual file and code you are working on.

---

## How to Access Templates

- Click the **Templates** dropdown button in the Prism toolbar.
- To create a new template immediately, choose **Create new...** from the dropdown.

---

## Available Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `{selection}` | Selected text in the editor | code you highlighted |
| `{file}` | Active file path | `src/main/kotlin/Main.kt` |
| `{language}` | Language of the active file | `kotlin`, `python`, `javascript` |

---

## Example Template

```
Please review this {language} code:

{selection}

Focus: performance, readability, best practices
```

When applied to a selected Kotlin snippet, the prompt sent to the agent becomes:

```
Please review this kotlin code:

fun add(a: Int, b: Int) = a + b

Focus: performance, readability, best practices
```

---

## How to Use a Template

1. Select the code you want to include in the prompt in the editor.
2. Click **Templates** in the Prism toolbar.
3. Choose a template from the list.
4. Variables are automatically substituted with values from the current editor context.
5. The completed prompt is sent to the agent.

---

## Managing Templates

Open **Templates > Manage Templates...** from the toolbar dropdown.

A dialog appears with the full list of your templates. From this dialog you can:

- **New** — create a template from scratch.
- **Edit** — modify the name or body of an existing template.
- **Delete** — permanently remove a template.

Templates are stored locally in:

```
~/.claude/prompt-templates.json
```

---

## Example: Code Review Template

The following template covers a structured code review across multiple dimensions:

```
Code Review for {language}

File: {file}

Review:
{selection}

Checklist:
- Performance
- Security
- Tests
- Documentation
```

Save this as "Code Review" and apply it whenever you want a consistent, thorough review without rewriting the prompt each time.

---

## Tip

Templates work best for tasks you perform repeatedly. Consider creating a template for each of the following:

- **Review** — review code for quality and correctness
- **Fix** — ask the agent to fix a specific issue
- **Test** — generate unit tests for selected code
- **Refactor** — request a cleaner implementation
- **Explain** — get a plain-language explanation of unfamiliar code

The more precisely your template describes the task, the more focused and actionable the agent's response will be.
