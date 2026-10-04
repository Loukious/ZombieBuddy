package testjar;

// package-private type/field; patch access via VarHandle + MethodHandles.privateLookupIn factory
class ShadowTarget {
    private int privateField = 69;
    private int combined;

    private int privateMethod() {
        return 42;
    }

    // multi-arg, void-returning method: exercises the MethodHandle call-site rewrite for an
    // arity/return shape that isn't "zero explicit args, returns int"
    private void combine(int a, String b) {
        combined = a + b.length();
    }
}
