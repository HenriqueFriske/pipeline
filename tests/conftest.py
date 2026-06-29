import os
import sys

# Make `main` and `core` importable regardless of the process CWD. Tests that
# chdir into a tmp dir (to avoid polluting the project tree) would otherwise lose
# the implicit CWD entry on sys.path and fail to import `main`.
_PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _PROJECT_ROOT not in sys.path:
    sys.path.insert(0, _PROJECT_ROOT)
