import lldb

from lldb_providers import *
from rust_types import *


def classify_rust_type(type):
    type_class = type.GetTypeClass()
    if type_class == lldb.eTypeClassStruct:
        return classify_struct(type.name, type.fields)
    if type_class == lldb.eTypeClassUnion:
        return classify_union(type.fields)

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

    return ""


def synthetic_lookup(valobj, dict):
    # type: (SBValue, dict) -> object
    """Returns the synthetic provider for the given value"""
    rust_type = classify_rust_type(valobj.GetType())

    if rust_type == RustType.STD_VEC:
        return StdVecSyntheticProvider(valobj, dict)
    if rust_type == RustType.STRUCT:
        return StructSyntheticProvider(valobj, dict)
    if rust_type == RustType.STRUCT_VARIANT:
        return StructSyntheticProvider(valobj, dict, is_variant=True)
    if rust_type == RustType.TUPLE:
        return TupleSyntheticProvider(valobj, dict)
    if rust_type == RustType.TUPLE_VARIANT:
        return TupleSyntheticProvider(valobj, dict, is_variant=True)
    if rust_type == RustType.EMPTY:
        return EmptySyntheticProvider(valobj, dict)

    if rust_type == RustType.REGULAR_ENUM:
        discriminant = valobj.GetChildAtIndex(0).GetChildAtIndex(0).GetValueAsUnsigned()
        return synthetic_lookup(valobj.GetChildAtIndex(discriminant), dict)
    if rust_type == RustType.SINGLETON_ENUM:
        return synthetic_lookup(valobj.GetChildAtIndex(0), dict)

    return DefaultSynthteticProvider(valobj, dict)
