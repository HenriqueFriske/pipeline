import re

def _parse_and_validate_candidate(candidate: str) -> str:
    """
    State-aware parser that tracks brace balance and truncates trailing content.
    Skips braces inside single-line/multi-line comments and string/character literals,
    handling escaped characters correctly.
    """
    i = 0
    n = len(candidate)
    balance = 0
    parenthesis_balance = 0
    first_brace_found = False
    truncated_idx = None
    state = "NORMAL"
    escape = False

    while i < n:
        char = candidate[i]

        if state == "NORMAL":
            if char == '/' and i + 1 < n and candidate[i+1] == '/':
                state = "SINGLE_LINE_COMMENT"
                i += 2
                continue
            elif char == '/' and i + 1 < n and candidate[i+1] == '*':
                state = "MULTI_LINE_COMMENT"
                i += 2
                continue
            elif char == '"' and i + 2 < n and candidate[i+1] == '"' and candidate[i+2] == '"':
                state = "TEXT_BLOCK"
                escape = False
                i += 3
                continue
            elif char == '"':
                state = "STRING_LITERAL"
                escape = False
                i += 1
                continue
            elif char == "'":
                state = "CHAR_LITERAL"
                escape = False
                i += 1
                continue
            elif char == '(':
                parenthesis_balance += 1
                i += 1
                continue
            elif char == ')':
                parenthesis_balance -= 1
                i += 1
                continue
            elif char == '{':
                if parenthesis_balance == 0:
                    balance += 1
                    first_brace_found = True
            elif char == '}':
                if parenthesis_balance == 0:
                    balance -= 1
                    if first_brace_found and balance == 0:
                        truncated_idx = i + 1
                        break
            i += 1
        elif state == "SINGLE_LINE_COMMENT":
            if char == '\n':
                state = "NORMAL"
            i += 1
        elif state == "MULTI_LINE_COMMENT":
            if char == '*' and i + 1 < n and candidate[i+1] == '/':
                state = "NORMAL"
                i += 2
                continue
            i += 1
        elif state == "TEXT_BLOCK":
            if escape:
                escape = False
                i += 1
                continue
            if char == '\\':
                escape = True
                i += 1
                continue
            if char == '"' and i + 2 < n and candidate[i+1] == '"' and candidate[i+2] == '"':
                state = "NORMAL"
                i += 3
                continue
            i += 1
        elif state == "STRING_LITERAL":
            if escape:
                escape = False
                i += 1
                continue
            if char == '\\':
                escape = True
                i += 1
                continue
            if char == '"':
                state = "NORMAL"
            i += 1
        elif state == "CHAR_LITERAL":
            if escape:
                escape = False
                i += 1
                continue
            if char == '\\':
                escape = True
                i += 1
                continue
            if char == "'":
                state = "NORMAL"
            i += 1

    if not first_brace_found:
        raise ValueError("Nenhuma chave '{' encontrada no candidato.")

    if truncated_idx is not None:
        return candidate[:truncated_idx].strip()

    if balance != 0:
        raise ValueError("Código Java truncado ou com chaves desbalanceadas")

    return candidate.strip()

def extract_java_block(llm_response: str) -> str:
    """
    Extracts a Java code block from the LLM response.
    Tries regex search for ```java ... ``` first, then falls back to heuristic scan.
    Validates brace balance.
    """
    # 1. Regex search for markdown block (case insensitive)
    pattern = r"```java\s*([\s\S]*?)\s*```"
    match = re.search(pattern, llm_response, re.IGNORECASE)
    if match:
        candidate = match.group(1).strip()
        return _parse_and_validate_candidate(candidate)

    # 2. Heuristic fallback scan
    heuristic_patterns = [
        r"\bpackage\s+[a-zA-Z0-9_.]+\s*;",
        r"\bimport\s+[a-zA-Z0-9_.*]+\s*;",
        r"(?:@\w+(?:\([^)]*\))?\s+)*\b(?:(?:public|private|protected|abstract|final|sealed|non-sealed|static)\s+)*(?:class|interface|enum|record)\s+[a-zA-Z_$]\w*",
        r"\b(?:public\s+|private\s+|protected\s+)(?:static\s+)?\w+(?:<[^>]+>)?\s+[a-zA-Z_$]\w*\s*\("
    ]

    earliest_start = None
    for pat in heuristic_patterns:
        for m in re.finditer(pat, llm_response):
            start_idx = m.start()
            if earliest_start is None or start_idx < earliest_start:
                earliest_start = start_idx

    if earliest_start is not None:
        candidate = llm_response[earliest_start:].strip()
        return _parse_and_validate_candidate(candidate)

    # 3. Not found
    return ""

