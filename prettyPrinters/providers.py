from lldb import SBValue, SBType, SBData, SBError, eBasicTypeUnsignedLong, formatters


#################################################################################################################
# This file contains two kinds of pretty-printers: summary and synthetic. I will try to explain how is it work.
#
# First of all, let's take a look at classes from LLDB module we will work with:
#   SBValue: the value of a variable, a register, or an expression
#   SBType:  the data type; each SBValue has a corresponding SBType
#
# Summary provider is a function with the type `(SBValue, dict) -> str`.
#   The first parameter is the object encapsulating the actual variable being displayed;
#   The second parameter is an internal support parameter used by LLDB and you should not touch it.
#
# Synthetic children is the way to provide a children-based user-friendly representation of the object's value.
# Synthetic provider is a class that implements the following interface:
#
#     class SyntheticChildrenProvider:
#         def __init__(self, SBValue, dict)
#         def num_children(self)
#         def get_child_index(self, str)
#         def get_child_at_index(self, int)
#         def update(self)
#         def has_children(self)
#         def get_value(self)
#
#
# You can find more information and examples here:
#   1. https://lldb.llvm.org/varformats.html
#   2. https://lldb.llvm.org/python-reference.html
#   3. https://lldb.llvm.org/python_reference/lldb.formatters.cpp.libcxx-pysrc.html
#   4. https://github.com/llvm-mirror/lldb/tree/master/examples/summaries/cocoa
################################################################################################################


def SizeSummaryProvider(valobj, dict):
    # type: (SBValue, dict) -> str
    return 'size=' + str(valobj.GetNumChildren())


class StdVecSyntheticProvider:
    """Pretty-printer for alloc::vec::Vec<T>

    struct Vec<T> { buf: RawVec<T>, len: usize }
    struct RawVec<T> { ptr: Unique<T>, cap: usize, ... }
    struct Unique<T: ?Sized> { pointer: NonZero<*const T>, ... }
    struct NonZero<T>(T)
    """

    def __init__(self, valobj, dict):
        # type: (SBValue, dict) -> StdVecSyntheticProvider
        logger = formatters.Logger.Logger()
        self.valobj = valobj
        self.update()
        logger >> "Providing synthetic children for a Vec named " + str(valobj.GetName())

    def num_children(self):
        # type: () -> int
        return self.length

    def get_child_index(self, name):
        # type: (str) -> int
        index = name.lstrip('[').rstrip(']')
        if index.isdigit():
            return int(index)
        else:
            return -1

    def get_child_at_index(self, index):
        # type: (int) -> SBValue
        start = self.data_ptr.GetValueAsUnsigned()
        address = start + index * self.element_type_size
        element = self.data_ptr.CreateValueFromAddress("[%s]" % index, address, self.element_type)
        return element

    def update(self):
        # type: () -> None
        self.length = self.valobj.GetChildMemberWithName("len").GetValueAsUnsigned()  # type: int
        self.buf = self.valobj.GetChildMemberWithName("buf")  # type: SBValue
        self.data_ptr = self.buf.GetChildAtIndex(0).GetChildAtIndex(0).GetChildAtIndex(0)  # type: SBValue
        self.element_type = self.data_ptr.GetType().GetPointeeType()  # type: SBType
        self.element_type_size = self.element_type.GetByteSize()  # type: int

    def has_children(self):
        # type: () -> bool
        return True


def StdStringSummaryProvider(valobj, dict):
    # type: (SBValue, dict) -> str
    assert valobj.GetNumChildren() == 1

    vec = valobj.GetChildAtIndex(0)
    length = vec.GetNumChildren()
    chars = [chr(vec.GetChildAtIndex(i).GetValueAsUnsigned()) for i in range(length)]
    return "".join(chars)


def StdStrSummaryProvider(valobj, dict):
    # type: (SBValue, dict) -> str
    assert valobj.GetNumChildren() == 2

    length = valobj.GetChildMemberWithName("length").GetValueAsUnsigned()
    data_ptr = valobj.GetChildMemberWithName("data_ptr")

    start = data_ptr.GetValueAsUnsigned()
    error = SBError()
    process = data_ptr.GetProcess()
    data = process.ReadMemory(start, length, error)
    if error.Success():
        return '"%s"' % data.decode(encoding='UTF-8')
    else:
        return '<error: %s>' % error.GetCString()


class StdRcSyntheticProvider:
    """Pretty-printer for alloc::rc::Rc<T>

    struct Rc<T> { ptr: NonNull<RcBox<T>>, ... }
    struct NonNull<T> { pointer: NonZero<*const T> }
    struct NonZero<T>(T)
    struct RcBox<T> { strong: Cell<usize>, weak: Cell<usize>, value: T }
    struct Cell<T> { value: UnsafeCell<T> }
    struct UnsafeCell<T> { value: T }
    """

    def __init__(self, valobj, dict):
        # type: (SBValue, dict) -> StdRcSyntheticProvider
        self.valobj = valobj

        self.ptr = self.valobj.GetChildMemberWithName("ptr").GetChildMemberWithName("pointer").GetChildAtIndex(0)
        self.value = self.ptr.GetChildMemberWithName("value")

        self.strong = self.ptr.GetChildMemberWithName("strong").GetChildMemberWithName("value").GetChildMemberWithName(
            "value")
        self.weak = self.ptr.GetChildMemberWithName("weak").GetChildMemberWithName("value").GetChildMemberWithName(
            "value")

        process = valobj.GetProcess()
        self.endianness = process.GetByteOrder()
        self.pointer_size = process.GetAddressByteSize()
        self.count_type = valobj.GetType().GetBasicType(eBasicTypeUnsignedLong)

    def num_children(self):
        # type: () -> int
        return 3

    def get_child_index(self, name):
        # type: (str) -> int
        if name == "strong":
            return 0
        if name == "weak":
            return 1
        if name == "value":
            return 2
        return -1

    def get_child_at_index(self, index):
        # type: (int) -> SBValue
        if index == 0:
            count = self.strong.GetValueAsSigned()
            return self.valobj.CreateValueFromData("strong",
                                                   SBData.CreateDataFromUInt64Array(self.endianness, self.pointer_size,
                                                                                    [count]),
                                                   self.count_type)
        if index == 1:
            count = self.weak.GetValueAsSigned()
            return self.valobj.CreateValueFromData("weak",
                                                   SBData.CreateDataFromUInt64Array(self.endianness, self.pointer_size,
                                                                                    [count]),
                                                   self.count_type)
        if index == 2:
            return self.value

        return None

    def update(self):
        pass

    def has_children(self):
        # type: () -> bool
        return True


def StdRcSummaryProvider(valobj, dict):
    # type: (SBValue, dict) -> str
    assert valobj.GetNumChildren() == 3

    strong = valobj.GetChildMemberWithName("strong").GetValueAsUnsigned()
    weak = valobj.GetChildMemberWithName("weak").GetValueAsUnsigned()
    return "strong={}, weak={}".format(strong, weak)


class StdHashMapSyntheticProvider:
    """Pretty-printer for std::collections::hash::map::HashMap<K, V, S>

    struct HashMap<K, V, S> {..., table: RawTable<K, V>, ... }
    struct RawTable<K, V> { capacity_mask: usize, size: usize, hashes: TaggedHashUintPtr, ... }
    """

    def __init__(self, valobj, dict):
        # type: (SBValue, dict) -> StdHashMapSyntheticProvider
        self.valobj = valobj
        self.update()

    def num_children(self):
        # type: () -> int
        return self.size

    def get_child_index(self, name):
        # type: (str) -> int
        index = name.lstrip('[').rstrip(']')
        return -1

    def get_child_at_index(self, index):
        # type: (int) -> SBValue
        return None

    def update(self):
        # type: () -> None
        logger = formatters.Logger.Logger()

        self.table = self.valobj.GetChildMemberWithName("table")  # type: SBValue
        self.size = self.table.GetChildMemberWithName("size").GetValueAsUnsigned()
        self.hashes = self.table.GetChildMemberWithName("hashes")
        self.tagged_hash_uint = self.hashes.GetChildAtIndex(0).GetChildAtIndex(0)

        ptr = self.tagged_hash_uint.GetValueAsUnsigned() & ~1
        self.hash_start = ptr
        self.pair_start = ptr + 2  # ???
        self.idx = 0

        self.pair_type = self.table.GetChildMemberWithName("marker").GetType()  # type: SBType
        logger >> "HashMap type: " + self.pair_type.GetName()

        self.pair_type_size = self.pair_type.GetByteSize()  # type: int
        logger >> "HashMap type size: " + str(self.pair_type_size)

    def has_children(self):
        # type: () -> bool
        return False
