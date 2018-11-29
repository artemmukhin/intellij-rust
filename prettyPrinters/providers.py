from lldb import SBValue, SBData, SBError, eBasicTypeLong, eBasicTypeUnsignedLong
from lldb.formatters import Logger


#################################################################################################################
# This file contains two kinds of pretty-printers: summary and synthetic.
#
# Important classes from LLDB module:
#   SBValue: the value of a variable, a register, or an expression
#   SBType:  the data type; each SBValue has a corresponding SBType
#
# Summary provider is a function with the type `(SBValue, dict) -> str`.
#   The first parameter is the object encapsulating the actual variable being displayed;
#   The second parameter is an internal support parameter used by LLDB, and you should not touch it.
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


class ValueBuilder:
    def __init__(self, valobj):
        # type: (SBValue) -> ValueBuilder
        self.valobj = valobj
        process = valobj.GetProcess()
        self.endianness = process.GetByteOrder()
        self.pointer_size = process.GetAddressByteSize()

    def from_int(self, name, value):
        # type: (str, int) -> SBValue
        type = self.valobj.GetType().GetBasicType(eBasicTypeLong)
        data = SBData.CreateDataFromSInt64Array(self.endianness, self.pointer_size, [value])
        return self.valobj.CreateValueFromData(name, data, type)

    def from_uint(self, name, value):
        # type: (str, int) -> SBValue
        type = self.valobj.GetType().GetBasicType(eBasicTypeUnsignedLong)
        data = SBData.CreateDataFromUInt64Array(self.endianness, self.pointer_size, [value])
        return self.valobj.CreateValueFromData(name, data, type)


class DefaultSynthteticProvider:
    def __init__(self, valobj, dict):
        # type: (SBValue, dict) -> DefaultSynthteticProvider
        logger = Logger.Logger()
        logger >> "Default synthetic provider for " + str(valobj.GetName())
        self.valobj = valobj

    def num_children(self):
        # type: () -> int
        return self.valobj.GetNumChildren()

    def get_child_index(self, name):
        # type: (str) -> int
        return self.valobj.GetIndexOfChildWithName(name)

    def get_child_at_index(self, index):
        # type: (int) -> SBValue
        return self.valobj.GetChildAtIndex(index)

    def update(self):
        # type: () -> None
        pass

    def has_children(self):
        # type: () -> bool
        return self.valobj.MightHaveChildren()


class EmptySyntheticProvider:
    def __init__(self, valobj, dict):
        # type: (SBValue, dict) -> EmptySyntheticProvider
        logger = Logger.Logger()
        logger >> "[EmptySyntheticProvider] for " + str(valobj.GetName())
        self.valobj = valobj

    def num_children(self):
        # type: () -> int
        return 0

    def get_child_index(self, name):
        # type: (str) -> int
        return None

    def get_child_at_index(self, index):
        # type: (int) -> SBValue
        return None

    def update(self):
        # type: () -> None
        pass

    def has_children(self):
        # type: () -> bool
        return False


def SizeSummaryProvider(valobj, dict):
    # type: (SBValue, dict) -> str
    return 'size=' + str(valobj.GetNumChildren())


def StdStringSummaryProvider(valobj, dict):
    # type: (SBValue, dict) -> str
    assert valobj.GetNumChildren() == 1
    logger = Logger.Logger()
    logger >> "[StdStringSummaryProvider] for " + str(valobj.GetName())

    vec = valobj.GetChildAtIndex(0)
    length = vec.GetNumChildren()
    chars = [chr(vec.GetChildAtIndex(i).GetValueAsUnsigned()) for i in range(length)]
    return '"%s"' % "".join(chars)


def StdStrSummaryProvider(valobj, dict):
    # type: (SBValue, dict) -> str
    assert valobj.GetNumChildren() == 2
    logger = Logger.Logger()
    logger >> "[StdStrSummaryProvider] for " + str(valobj.GetName())

    length = valobj.GetChildMemberWithName("length").GetValueAsUnsigned()
    data_ptr = valobj.GetChildMemberWithName("data_ptr")

    start = data_ptr.GetValueAsUnsigned()
    error = SBError()
    process = data_ptr.GetProcess()
    data = process.ReadMemory(start, length, error)
    if error.Success():
        return '"%s"' % data
    else:
        return '<error: %s>' % error.GetCString()


class StructSyntheticProvider:
    """Pretty-printer for structs and struct enum variants"""

    def __init__(self, valobj, dict, is_variant=False):
        # type: (SBValue, dict, bool) -> StructSyntheticProvider
        logger = Logger.Logger()
        self.valobj = valobj
        self.is_variant = is_variant
        self.type = valobj.GetType()
        self.fields = {}

        if is_variant:
            logger >> "[StructVariantSyntheticProvider] for " + str(valobj.GetName())
            self.fields_count = self.type.GetNumberOfFields() - 1
            real_fields = self.type.fields[1:]
        else:
            logger >> "[StructSyntheticProvider] for " + str(valobj.GetName())
            self.fields_count = self.type.GetNumberOfFields()
            real_fields = self.type.fields

        for number, field in enumerate(real_fields):
            self.fields[field.name] = number

    def num_children(self):
        # type: () -> int
        return self.fields_count

    def get_child_index(self, name):
        # type: (str) -> int
        return self.fields.get(name, -1)

    def get_child_at_index(self, index):
        # type: (int) -> SBValue
        if self.is_variant:
            field = self.type.GetFieldAtIndex(index + 1)
        else:
            field = self.type.GetFieldAtIndex(index)
        return self.valobj.GetChildMemberWithName(field.name)

    def update(self):
        # type: () -> None
        pass

    def has_children(self):
        # type: () -> bool
        return True


class TupleSyntheticProvider:
    """Pretty-printer for tuples and tuple enum variants"""

    def __init__(self, valobj, dict, is_variant=False):
        # type: (SBValue, dict, bool) -> TupleSyntheticProvider
        logger = Logger.Logger()
        self.valobj = valobj
        self.is_variant = is_variant
        self.type = valobj.GetType()

        if is_variant:
            logger >> "[TupleVariantSyntheticProvider] for " + str(valobj.GetName())
            self.size = self.type.GetNumberOfFields() - 1
        else:
            logger >> "[TupleSyntheticProvider] for " + str(valobj.GetName())
            self.size = self.type.GetNumberOfFields()

    def num_children(self):
        # type: () -> int
        return self.size

    def get_child_index(self, name):
        # type: (str) -> int
        if name.isdigit():
            return int(name)
        else:
            return -1

    def get_child_at_index(self, index):
        # type: (int) -> SBValue
        if self.is_variant:
            field = self.type.GetFieldAtIndex(index + 1)
        else:
            field = self.type.GetFieldAtIndex(index)
        element = self.valobj.GetChildMemberWithName(field.name)
        return self.valobj.CreateValueFromData(str(index), element.GetData(), element.GetType())

    def update(self):
        # type: () -> None
        pass

    def has_children(self):
        # type: () -> bool
        return True


class StdVecSyntheticProvider:
    """Pretty-printer for alloc::vec::Vec<T>

    struct Vec<T> { buf: RawVec<T>, len: usize }
    struct RawVec<T> { ptr: Unique<T>, cap: usize, ... }
    struct Unique<T: ?Sized> { pointer: NonZero<*const T>, ... }
    struct NonZero<T>(T)
    """

    def __init__(self, valobj, dict):
        # type: (SBValue, dict) -> StdVecSyntheticProvider
        logger = Logger.Logger()
        logger >> "[StdVecSyntheticProvider] for " + str(valobj.GetName())
        self.valobj = valobj
        self.update()

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
        self.length = self.valobj.GetChildMemberWithName("len").GetValueAsUnsigned()
        self.buf = self.valobj.GetChildMemberWithName("buf")
        self.data_ptr = self.buf.GetChildAtIndex(0).GetChildAtIndex(0).GetChildAtIndex(0)
        self.element_type = self.data_ptr.GetType().GetPointeeType()
        self.element_type_size = self.element_type.GetByteSize()

    def has_children(self):
        # type: () -> bool
        return True


class StdVecDequeSyntheticProvider:
    """Pretty-printer for alloc::collections::vec_deque::VecDeque<T>

    struct VecDeque<T> { tail: usize, head: usize, buf: RawVec<T> }
    """

    def __init__(self, valobj, dict):
        # type: (SBValue, dict) -> StdVecDequeSyntheticProvider
        logger = Logger.Logger()
        logger >> "[StdVecDequeSyntheticProvider] for " + str(valobj.GetName())
        self.valobj = valobj
        self.update()

    def num_children(self):
        # type: () -> int
        return self.size

    def get_child_index(self, name):
        # type: (str) -> int
        index = name.lstrip('[').rstrip(']')
        if index.isdigit() and self.tail <= index and (self.tail + index) % self.cap < self.head:
            return int(index)
        else:
            return -1

    def get_child_at_index(self, index):
        # type: (int) -> SBValue
        start = self.data_ptr.GetValueAsUnsigned()
        address = start + ((index + self.tail) % self.cap) * self.element_type_size
        element = self.data_ptr.CreateValueFromAddress("[%s]" % index, address, self.element_type)
        return element

    def update(self):
        # type: () -> None
        self.head = self.valobj.GetChildMemberWithName("head").GetValueAsUnsigned()
        self.tail = self.valobj.GetChildMemberWithName("tail").GetValueAsUnsigned()
        self.buf = self.valobj.GetChildMemberWithName("buf")
        self.cap = self.buf.GetChildMemberWithName("cap").GetValueAsUnsigned()
        self.size = self.head - self.tail if self.head >= self.tail else self.cap + self.head - self.tail
        self.data_ptr = self.buf.GetChildMemberWithName("ptr").GetChildAtIndex(0).GetChildAtIndex(0)
        self.element_type = self.data_ptr.GetType().GetPointeeType()
        self.element_type_size = self.element_type.GetByteSize()

    def has_children(self):
        # type: () -> bool
        return True


def StdRcSummaryProvider(valobj, dict):
    # type: (SBValue, dict) -> str
    strong = valobj.GetChildMemberWithName("strong").GetValueAsUnsigned()
    weak = valobj.GetChildMemberWithName("weak").GetValueAsUnsigned()
    return "strong={}, weak={}".format(strong, weak)


class StdRcSyntheticProvider:
    """Pretty-printer for alloc::rc::Rc<T> and alloc::sync::Arc<T>

    struct Rc<T> { ptr: NonNull<RcBox<T>>, ... }
    struct NonNull<T> { pointer: NonZero<*const T> }
    struct NonZero<T>(T)
    struct RcBox<T> { strong: Cell<usize>, weak: Cell<usize>, value: T }
    struct Cell<T> { value: UnsafeCell<T> }
    struct UnsafeCell<T> { value: T }

    struct Arc<T> { ptr: NonNull<ArcInner<T>>, ... }
    struct NonNull<T> { pointer: NonZero<*const T> }
    struct NonZero<T>(T)
    struct ArcInner<T> { strong: atomic::AtomicUsize, weak: atomic::AtomicUsize, data: T }
    struct AtomicUsize { v: UnsafeCell<usize> }
    struct UnsafeCell<T> { value: T }
    """

    def __init__(self, valobj, dict, is_atomic=False):
        # type: (SBValue, dict, bool) -> StdRcSyntheticProvider
        self.valobj = valobj

        self.ptr = self.valobj.GetChildMemberWithName("ptr").GetChildMemberWithName("pointer").GetChildAtIndex(0)
        self.value = self.ptr.GetChildMemberWithName("data" if is_atomic else "value")

        self.strong = self.ptr.GetChildMemberWithName("strong").GetChildAtIndex(0).GetChildMemberWithName("value")
        self.weak = self.ptr.GetChildMemberWithName("weak").GetChildAtIndex(0).GetChildMemberWithName("value")

        self.value_builder = ValueBuilder(valobj)

        self.update()

    def num_children(self):
        # type: () -> int
        # Actually there are 3 children, but only the `value` should be shown as a child
        return 1

    def get_child_index(self, name):
        # type: (str) -> int
        if name == "value":
            return 0
        if name == "strong":
            return 1
        if name == "weak":
            return 2
        return -1

    def get_child_at_index(self, index):
        # type: (int) -> SBValue
        if index == 0:
            return self.value
        if index == 1:
            return self.value_builder.from_uint("strong", self.strong_count)
        if index == 2:
            return self.value_builder.from_uint("weak", self.weak_count)

        return None

    def update(self):
        # type: () -> None
        self.strong_count = self.strong.GetValueAsUnsigned()
        self.weak_count = self.weak.GetValueAsUnsigned()

    def has_children(self):
        # type: () -> bool
        return True


class StdCellSyntheticProvider:
    """Pretty-printer for std::cell::Cell"""

    def __init__(self, valobj, dict):
        # type: (SBValue, dict) -> StdCellSyntheticProvider
        self.valobj = valobj
        self.value = valobj.GetChildMemberWithName("value").GetChildAtIndex(0)

    def num_children(self):
        # type: () -> int
        return 1

    def get_child_index(self, name):
        # type: (str) -> int
        if name == "value":
            return 0
        return -1

    def get_child_at_index(self, index):
        # type: (int) -> SBValue
        if index == 0:
            return self.value
        return None

    def update(self):
        # type: () -> None
        pass

    def has_children(self):
        # type: () -> bool
        return True


def StdRefSummaryProvider(valobj, dict):
    # type: (SBValue, dict) -> str
    borrow = valobj.GetChildMemberWithName("borrow").GetValueAsSigned()
    return "borrow={}".format(borrow) if borrow >= 0 else "borrow_mut={}".format(-borrow)


class StdRefSyntheticProvider:
    """Pretty-printer for std::cell::Ref, std::cell::RefMut, and std::cell::RefCell"""

    def __init__(self, valobj, dict, is_cell=False):
        # type: (SBValue, dict, bool) -> StdRefSyntheticProvider
        self.valobj = valobj
        self.borrow = valobj.GetChildMemberWithName("borrow").GetChildAtIndex(0).GetChildAtIndex(0)
        if not is_cell:
            self.borrow = self.borrow.GetChildAtIndex(0)
        self.value = valobj.GetChildMemberWithName("value").GetChildAtIndex(0)

        self.value_builder = ValueBuilder(valobj)

        self.update()

    def num_children(self):
        # type: () -> int
        # Actually there are 2 children, but only the `value` should be shown as a child
        return 1

    def get_child_index(self, name):
        if name == "value":
            return 0
        if name == "borrow":
            return 1
        return -1

    def get_child_at_index(self, index):
        # type: (int) -> SBValue
        if index == 0:
            return self.value
        if index == 1:
            return self.value_builder.from_int("borrow", self.borrow_count)
        return None

    def update(self):
        # type: () -> None
        self.borrow_count = self.borrow.GetValueAsSigned()

    def has_children(self):
        # type: () -> bool
        return True


def StdBTreeMapSummaryProvider(valobj, dict):
    # type: (SBValue, dict) -> str
    return 'size=' + str(valobj.GetNumChildren() / 2)


def children_of_node(node, height, want_values):
    ptr = node.GetChildMemberWithName("ptr").GetChildMemberWithName("pointer")
    node_field = ptr.type.GetFieldAtIndex(0)
    node_ptr = ptr.GetChildMemberWithName(node_field.name)
    if height > 0:
        pointee_type = node_ptr.type.GetPointeeType()
        type_name = pointee_type.GetName().replace('LeafNode', 'InternalNode')
        module = node.GetFrame().GetModule()
        node_type = module.FindFirstType(type_name)
        node_ptr = node_ptr.Cast(node_type.GetPointerType())
        leaf = node_ptr.GetChildMemberWithName("data")
    else:
        leaf = node_ptr.Dereference()

    keys = leaf.GetChildMemberWithName("keys").GetChildMemberWithName("value").GetChildMemberWithName("value")
    key_type = keys.GetType().GetArrayElementType()
    key_type_size = key_type.GetByteSize()

    if want_values:
        vals = leaf.GetChildMemberWithName("vals").GetChildMemberWithName("value").GetChildMemberWithName("value")
        val_type = vals.GetType().GetArrayElementType()
        val_type_size = val_type.GetByteSize()

    length = leaf.GetChildMemberWithName("len").GetValueAsUnsigned()

    for i in range(length + 1):
        if height > 0:
            edge = node_ptr.GetChildMemberWithName("edges").GetChildAtIndex(i)
            for child in children_of_node(edge, height - 1, want_values):
                yield child
        if i < length:
            key = keys.CreateChildAtOffset("key[%s]" % i, i * key_type_size, key_type)
            if want_values:
                val = vals.CreateChildAtOffset("val[%s]" % i, i * val_type_size, val_type)
                yield (key, val)
            else:
                yield key


class StdBTreeMapSyntheticProvider:
    """Pretty-printer for alloc::collections::btree::map::BTreeMap<K, V>"""

    def __init__(self, valobj, dict):
        # type: (SBValue, dict) -> StdBTreeMapSyntheticProvider
        logger = Logger.Logger()
        logger >> "[StdBTreeMapSyntheticProvider] for " + str(valobj.GetName())
        self.valobj = valobj
        self.keys = []
        self.values = []
        self.length = 0
        self.update()

    def num_children(self):
        # type: () -> int
        return self.length

    def get_child_index(self, name):
        # type: (str) -> int
        index = name.lstrip('[').rstrip(']')
        if index.isdigit() and index < self.length:
            return int(index)
        else:
            return -1

    def get_child_at_index(self, index):
        # type: (int) -> SBValue
        return self.keys[index]  # TODO: "return" both key and value

    def update(self):
        # type: () -> None
        root = self.valobj.GetChildMemberWithName("root")
        node = root.GetChildMemberWithName("node")
        height = root.GetChildMemberWithName("height").GetValueAsUnsigned()

        self.keys = []
        self.values = []
        for child in children_of_node(node, height, want_values=True):
            self.keys.append(child[0])
            self.values.append(child[1])

        self.length = len(self.values)

    def has_children(self):
        # type: () -> bool
        return True
