"""Backwards-compatible alias for :mod:`modules.modality_types`.

The shared contract types were renamed to ``modules.modality_types`` to avoid
shadowing the standard-library ``types`` module in ad-hoc scripts. Existing
imports of ``modules.types`` keep working through this re-export.
"""

from modules.modality_types import *  # noqa: F401,F403
