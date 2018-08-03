fn main() {
    println!("Hello, World");
    let u = MyUnion { f1: 1 };
}

union MyUnion {
    f1: u32,
    f2: f32,
}

struct S {}
