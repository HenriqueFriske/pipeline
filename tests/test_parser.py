import pytest
from core.parser import extract_java_block

def test_extract_java_block_markdown():
    # Test lowercase java block
    response_lower = "Here is code:\n```java\npublic class Test {\n    public void run() {}\n}\n```\nDone."
    assert extract_java_block(response_lower) == "public class Test {\n    public void run() {}\n}"

    # Test uppercase java block (case insensitive)
    response_upper = "Here is code:\n```JAVA\npublic class TestUpper {\n    public void run() {}\n}\n```"
    assert extract_java_block(response_upper) == "public class TestUpper {\n    public void run() {}\n}"

def test_extract_java_block_heuristic_fallback():
    # Test heuristic fallback for public class
    response_class = "Sure, here is the class:\npublic class MyClass {\n    public static void main(String[] args) {}\n}"
    assert extract_java_block(response_class) == "public class MyClass {\n    public static void main(String[] args) {}\n}"

    # Test heuristic fallback for interface
    response_interface = "Interface declaration:\npublic interface Service {\n    void execute();\n}"
    assert extract_java_block(response_interface) == "public interface Service {\n    void execute();\n}"

    # Test heuristic fallback for class without public
    response_simple_class = "Some text\nclass Helper {\n    int value;\n}"
    assert extract_java_block(response_simple_class) == "class Helper {\n    int value;\n}"

def test_extract_java_block_heuristic_annotations_and_modifiers():
    # Test fallback with annotation and modifier
    response = "Explanation:\n@SomeAnnotation\npublic class MyClass {\n    int x;\n}"
    assert extract_java_block(response) == "@SomeAnnotation\npublic class MyClass {\n    int x;\n}"

    # Test fallback with multiple annotations and modifiers
    response_multi = "Here it is:\n@Annotation1(val=1) @Annotation2\npublic final class Advanced {\n}"
    assert extract_java_block(response_multi) == "@Annotation1(val=1) @Annotation2\npublic final class Advanced {\n}"

def test_extract_java_block_truncated_unbalanced():
    # Unbalanced braces - open brace with no close brace
    response_truncated_1 = "```java\npublic class Truncated {\n    public void test() {\n```"
    with pytest.raises(ValueError) as excinfo:
        extract_java_block(response_truncated_1)
    assert any(word in str(excinfo.value).lower() for word in ["truncado", "desbalanceadas"])

    # Unbalanced braces - heuristic block
    response_truncated_2 = "public class TruncatedHeuristic {\n    int x;"
    with pytest.raises(ValueError) as excinfo:
        extract_java_block(response_truncated_2)
    assert any(word in str(excinfo.value).lower() for word in ["truncado", "desbalanceadas"])

def test_extract_java_block_not_found():
    # No java block or heuristic pattern found
    response_none = "This text has no java code inside it at all."
    assert extract_java_block(response_none) == ""

def test_extract_java_block_braces_in_comments_and_strings():
    # Braces inside comments and string literals should be ignored
    response = """
    public class CommentsAndStrings {
        // This is a comment with { and } braces
        /* This is a multi-line
           comment with { and } braces */
        String s1 = "Braces { and } inside string";
        String s2 = "Escaped \\" { and } inside string";
        char c = '{';
    }
    """
    # The block should be successfully extracted and match the input content
    result = extract_java_block(response)
    assert "public class CommentsAndStrings {" in result
    assert "char c = '{';" in result
    assert result.endswith("}")

def test_extract_java_block_trailing_explanation():
    # Trailing English explanation text after the code block should be truncated
    response = """
    public class Code {
        public void run() {}
    }
    Here is some trailing explanation that should be removed.
    """
    result = extract_java_block(response)
    assert result == "public class Code {\n        public void run() {}\n    }"

def test_extract_java_block_no_braces():
    # No braces should raise ValueError
    response = "public class NoBraces"
    with pytest.raises(ValueError) as excinfo:
        extract_java_block(response)
    assert "Nenhuma chave" in str(excinfo.value) or "no braces" in str(excinfo.value).lower()

def test_extract_java_block_unmatched_braces_fails():
    # Unmatched braces (final balance not 0) should raise ValueError
    response_open = "public class Open { public void run() {"
    with pytest.raises(ValueError) as excinfo:
        extract_java_block(response_open)
    assert any(word in str(excinfo.value).lower() for word in ["truncado", "desbalanceadas"])

    # Extra close braces after the block is closed are treated as trailing content and truncated
    response_extra_close = "public class ExtraClose { } }"
    assert extract_java_block(response_extra_close) == "public class ExtraClose { }"

def test_extract_java_block_with_annotation_braces():
    # Annotation with braces inside parenthesis: @Annotation({"val1", "val2"})
    response = """
    @MyAnnotation(values = {"val1", "val2"})
    public class ClassWithAnnotationBraces {
        public void run() {
            System.out.println("Hello");
        }
    }
    """
    result = extract_java_block(response)
    assert "@MyAnnotation(values = {\"val1\", \"val2\"})" in result
    assert "public class ClassWithAnnotationBraces" in result
    assert result.endswith("}")

def test_extract_java_block_with_text_blocks():
    # Text block with braces inside: """ { nested } """
    response = """
    public class TextBlockClass {
        String block = \"\"\"
        {
            "key": "value"
        }
        \"\"\";
    }
    """
    result = extract_java_block(response)
    assert "public class TextBlockClass {" in result
    assert 'String block = """' in result
    assert '}' in result
    assert result.endswith("}")


