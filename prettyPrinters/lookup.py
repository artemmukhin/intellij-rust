import re
from lldb import eTypeClassStruct

from providers import *


class RustType:
    OTHER = 0
    STD_VEC = 1
    STD_STRING = 2
    STD_STR = 3
    STD_RC = 4


STD_VEC_REGEX = re.compile(r"^(alloc::([a-zA-Z]+::)+)Vec<.+>$")
STD_STRING_REGEX = re.compile(r"^(alloc::([a-zA-Z]+::)+)String$")
STD_STR_REGEX = re.compile(r"^&str$")
STD_RC_REGEX = re.compile(r"^(alloc::([a-zA-Z]+::)+)Rc<.+>$")


def classify_rust_type(type):
    # type: (SBType) -> int
    type_class = type.GetTypeClass()

    if type_class == eTypeClassStruct:
        name = type.GetName()
        if re.match(STD_VEC_REGEX, name):
            return RustType.STD_VEC
        if re.match(STD_STRING_REGEX, name):
            return RustType.STD_STRING
        if re.match(STD_STR_REGEX, name):
            return RustType.STD_STR
        if re.match(STD_RC_REGEX, name):
            return RustType.STD_RC

    return RustType.OTHER


def summary_lookup(valobj, dict):
    # type: (SBValue, dict) -> str
    """Returns the summary provider for the given value"""
    rust_type = classify_rust_type(valobj.GetType())

    if rust_type == RustType.STD_VEC:
        return SizeSummaryProvider(valobj, dict)
    if rust_type == RustType.STD_STRING:
        return StdStringSummaryProvider(valobj, dict)
    if rust_type == RustType.STD_STR:
        return StdStrSummaryProvider(valobj, dict)
    if rust_type == RustType.STD_RC:
        return StdRcSummaryProvider(valobj, dict)

    return ""  # Important: return "", not None


def synthetic_lookup(valobj, dict):
    # type: (SBValue, dict) -> object
    """Returns the synthetic provider for the given value"""
    rust_type = classify_rust_type(valobj.GetType())

    if rust_type == RustType.STD_VEC:
        return StdVecSyntheticProvider(valobj, dict)
    if rust_type == RustType.STD_RC:
        return StdRcSyntheticProvider(valobj, dict)

    return None  # Important: return None, not valobj
