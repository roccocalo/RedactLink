## The "header"

```bash
#!/bin/bash
set -euo pipefail
```

**`#!/bin/bash`** is called a **shebang**. It tells the OS "run this file with bash." Without it, the system wouldn't know which interpreter to use.

**`set -euo pipefail`** is a safety net. It's four flags combined:
- `-e` → exit immediately if any command fails (otherwise bash keeps going, which is dangerous)
- `-u` → error if you use an undefined variable (catches typos like `$QUEUE_URI` instead of `$QUEUE_URL`)
- `-o pipefail` → if any command in a pipeline (`cmd1 | cmd2`) fails, the whole pipeline fails

Always put this at the top of serious scripts. It's the difference between "script silently did half the work" and "script stopped and told me exactly where."

## Variables

```bash
ACCOUNT_ID="1234"
REGION="us-east-1"
RAW_BUCKET="redactlink-raw-${ACCOUNT_ID}"
```

You **define** a variable with `NAME="value"` (no spaces around `=` — that matters in bash).
You **use** it with `$NAME` or `${NAME}`.

The curly braces `${ACCOUNT_ID}` are needed when you want to glue the variable to other text. `"redactlink-raw-${ACCOUNT_ID}"` becomes `"redactlink-raw-1234"`. Without braces, bash might think the variable name includes the following characters.

## Command substitution: `$(...)`

```bash
QUEUE_URL=$(aws sqs create-queue \
  --queue-name "$QUEUE_NAME" \
  --region "$REGION" \
  --query QueueUrl --output text)
```

`$(...)` means: **"run this command and capture its output into a variable."**

So the AWS CLI creates the queue, prints the queue URL, and that URL gets stored in `$QUEUE_URL`. Now you can reuse it later in the script.

The **backslash `\`** at the end of lines just means "this command continues on the next line" — purely for readability. It's one long command split across multiple lines.

## Heredocs: writing multi-line text to files

```bash
cat > "$POLICY_FILE" <<EOF
{
  "Version": "2012-10-17",
  ...
}
EOF
```

This is a **heredoc** (here-document). It's a clean way to write multi-line text into a file.

Breakdown:
- `cat > file` → "write to this file"
- `<<EOF` → "start reading input until you see a line with just EOF"
- Everything between the two `EOF` markers becomes the file content

`EOF` is just a convention — you could write `<<END` or `<<DONE`. It stands for "end of file."

**Important detail:** notice the difference between these two:
```bash
cat > "$POLICY_FILE" <<EOF       # variables ARE expanded → $QUEUE_ARN becomes the actual ARN
cat > "$CORS_FILE" <<'EOF'       # variables are NOT expanded (single quotes around EOF)
```

The script uses quoted `'EOF'` when the JSON has no variables to substitute, and unquoted `EOF` when it needs `$QUEUE_ARN` and `$RAW_BUCKET` replaced with real values.

## `mktemp`

```bash
POLICY_FILE=$(mktemp)
```

Creates a **temporary file** with a random unique name (like `/tmp/tmp.xYz123`) and returns its path. Used here because the AWS CLI wants a file path for JSON config, and writing to a random temp file avoids conflicts. Then `rm "$POLICY_FILE"` cleans it up after.

## Quoting: `"$VAR"` vs `$VAR`

You'll notice variables are almost always wrapped in double quotes: `"$QUEUE_ARN"`. **Always do this.** It protects you when values contain spaces or special characters. Bare `$VAR` can break in surprising ways.

## Putting a whole pattern together

Look at step 2:

```bash
POLICY_FILE=$(mktemp)                          # 1. make a temp file
cat > "$POLICY_FILE" <<EOF                     # 2. write JSON policy into it
{ ... "Resource": "$QUEUE_ARN" ... }
EOF
aws sqs set-queue-attributes \                 # 3. pass the file contents to AWS
  --queue-url "$QUEUE_URL" \
  --attributes "Policy=$(cat $POLICY_FILE)"
rm "$POLICY_FILE"                              # 4. clean up
```

This is a very common shell pattern: **generate a config file → pass it to a CLI → delete it.**

## The final `echo` block

At the bottom, the script just prints out `export` statements. It's **not running them** — it's showing you what to paste into your `~/.zshrc` so your Spring Boot app can read these values as environment variables.

## Tips for learning shell scripting

A few things that helped me:
1. **Write scripts for things you do repeatedly** — git cleanup, project setup, deployments. Motivation matters.
2. **Use `shellcheck`** — it's a linter that catches common bash mistakes. Install it and run `shellcheck myscript.sh`. It will teach you a lot.
3. **Run line-by-line first** — before automating, paste commands one at a time in your terminal and watch what happens.
4. **`bash -x script.sh`** runs a script in trace mode — you see every command as it executes with variables expanded. Amazing for debugging.

Want me to go deeper on any specific part — heredocs, variable expansion, error handling, or how the AWS commands themselves work?