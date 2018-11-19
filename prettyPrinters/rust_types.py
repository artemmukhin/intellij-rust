import re


class RustType:
    OTHER = "Other"
    STRUCT = "Struct"
    TUPLE = "Tuple"
    CSTYLE_VARIANT = "CStyleVariant"
    TUPLE_VARIANT = "TupleVariant"
    STRUCT_VARIANT = "StructVariant"
    EMPTY = "Empty"
    SINGLETON_ENUM = "SingletonEnum"
    REGULAR_ENUM = "RegularEnum"
    COMPRESSED_ENUM = "CompressedEnum"
    REGULAR_UNION = "RegularUnion"

    STD_VEC = "StdVec"
    STD_STRING = "StdString"
    STD_STR = "StdStr"


STD_VEC_REGEX = re.compile(r"^(alloc::([a-zA-Z]+::)+)Vec<.+>$")
STD_STRING_REGEX = re.compile(r"^(alloc::([a-zA-Z]+::)+)String$")
STD_STR_REGEX = re.compile(r"^&str$")

TUPLE_ITEM_REGEX = re.compile(r"__\d+$")

ENCODED_ENUM_PREFIX = "RUST$ENCODED$ENUM$"
ENUM_DISR_FIELD_NAME = "RUST$ENUM$DISR"


def is_tuple_fields(fields):
    # type: (list) -> bool
    return all(re.match(TUPLE_ITEM_REGEX, str(field.name)) for field in fields)


def classify_struct(name, fields):
    if len(fields) == 0:
        return RustType.EMPTY

    if re.match(STD_VEC_REGEX, name):
        return RustType.STD_VEC
    if re.match(STD_STRING_REGEX, name):
        return RustType.STD_STRING
    if re.match(STD_STR_REGEX, name):
        return RustType.STD_STR

    if fields[0].name == ENUM_DISR_FIELD_NAME:
        if len(fields) == 1:
            return RustType.CSTYLE_VARIANT
        if is_tuple_fields(fields[1:]):
            return RustType.TUPLE_VARIANT
        else:
            return RustType.STRUCT_VARIANT

    if is_tuple_fields(fields):
        return RustType.TUPLE

    else:
        return RustType.STRUCT


def classify_union(fields):
    if len(fields) == 0:
        return RustType.EMPTY

    first_variant_name = fields[0].name
    if first_variant_name is None:
        if len(fields) == 1:
            return RustType.SINGLETON_ENUM
        else:
            return RustType.REGULAR_ENUM
    elif first_variant_name.startswith(ENCODED_ENUM_PREFIX):
        assert len(fields) == 1
        return RustType.COMPRESSED_ENUM
    else:
        return RustType.REGULAR_UNION
