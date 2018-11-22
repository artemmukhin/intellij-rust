import gdb

from gdb_providers import *
from rust_types import *


def register_printers(objfile):
    """Registers Rust pretty printers for the given objfile"""
    objfile.pretty_printers.append(lookup)


def classify_rust_type(type):
    type_class = type.code
    if type_class == gdb.TYPE_CODE_STRUCT:
        return classify_struct(type.tag, type.fields())
    if type_class == gdb.TYPE_CODE_UNION:
        return classify_union(type.fields())

    return RustType.OTHER


def lookup(valobj):
    """Returns the provider for the given value"""
    rust_type = classify_rust_type(valobj.type)

    if rust_type == RustType.STRUCT:
        return StructProvider(valobj)
    if rust_type == RustType.STRUCT_VARIANT:
        return StructProvider(valobj, is_variant=True)
    if rust_type == RustType.TUPLE:
        return TupleProvider(valobj)
    if rust_type == RustType.TUPLE_VARIANT:
        return TupleProvider(valobj, is_variant=True)

    if rust_type == RustType.STD_STRING:
        return StdStringProvider(valobj)
    if rust_type == RustType.STD_STR:
        return StdStrProvider(valobj)
    if rust_type == RustType.STD_VEC:
        return StdVecProvider(valobj)
    if rust_type == RustType.STD_VEC_DEQUE:
        return StdVecDequeProvider(valobj)


    return None
