/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.layoutlib.create;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Analyzes the input JAR using the ASM java bytecode manipulation library
 * to list the desired classes and their dependencies.
 */
public class AsmAnalyzer {

    // Note: a bunch of stuff has package-level access for unit tests. Consider it private.

    /** Output logger. */
    private final Log mLog;
    /** The input source JAR to parse. */
    private final List<String> mOsSourceJar;
    /** The generator to fill with the class list and dependency list. */
    private final AsmGenerator mGen;
    /** Keep all classes that derive from these one (these included). */
    private final String[] mDeriveFrom;
    /** Glob patterns of classes to keep, e.g. "com.foo.*" */
    private final String[] mIncludeGlobs;

    /**
     * Creates a new analyzer.
     *
     * @param log The log output.
     * @param osJarPath The input source JARs to parse.
     * @param gen The generator to fill with the class list and dependency list.
     * @param deriveFrom Keep all classes that derive from these one (these included).
     * @param includeGlobs Glob patterns of classes to keep, e.g. "com.foo.*"
     *        ("*" does not matches dots whilst "**" does, "." and "$" are interpreted as-is)
     */
    public AsmAnalyzer(Log log, List<String> osJarPath, AsmGenerator gen,
            String[] deriveFrom, String[] includeGlobs) {
        mLog = log;
        mGen = gen;
        mOsSourceJar = osJarPath != null ? osJarPath : new ArrayList<String>();
        mDeriveFrom = deriveFrom != null ? deriveFrom : new String[0];
        mIncludeGlobs = includeGlobs != null ? includeGlobs : new String[0];
    }

    /**
     * Starts the analysis using parameters from the constructor.
     * Fills the generator with classes & dependencies found.
     */
    public void analyze() throws IOException, LogAbortException {
        Map<String, ClassReader> zipClasses = parseZip(mOsSourceJar);
        mLog.info("Found %d classes in input JAR%s.", zipClasses.size(),
                mOsSourceJar.size() > 1 ? "s" : "");

        Map<String, ClassReader> found = findIncludes(zipClasses);
        Map<String, ClassReader> deps = findDeps(zipClasses, found);

        if (mGen != null) {
            mGen.setKeep(found);
            mGen.setDeps(deps);
        }
    }

    /**
     * Parses a JAR file and returns a list of all classes founds using a map
     * class name => ASM ClassReader. Class names are in the form "android.view.View".
     */
    Map<String,ClassReader> parseZip(List<String> jarPathList) throws IOException {
        TreeMap<String, ClassReader> classes = new TreeMap<String, ClassReader>();

        for (String jarPath : jarPathList) {
            ZipFile zip = new ZipFile(jarPath);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            ZipEntry entry;
            while (entries.hasMoreElements()) {
                entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    ClassReader cr = new ClassReader(zip.getInputStream(entry));
                    String className = classReaderToClassName(cr);
                    classes.put(className, cr);
                }
            }
        }

        return classes;
    }

    /**
     * Utility that returns the fully qualified binary class name for a ClassReader.
     * E.g. it returns something like android.view.View.
     */
    static String classReaderToClassName(ClassReader classReader) {
        if (classReader == null) {
            return null;
        } else {
            return classReader.getClassName().replace('/', '.');
        }
    }

    /**
     * Utility that returns the fully qualified binary class name from a path-like FQCN.
     * E.g. it returns android.view.View from android/view/View.
     */
    static String internalToBinaryClassName(String className) {
        if (className == null) {
            return null;
        } else {
            return className.replace('/', '.');
        }
    }

    /**
     * Process the "includes" arrays.
     * <p/>
     * This updates the in_out_found map.
     */
    Map<String, ClassReader> findIncludes(Map<String, ClassReader> zipClasses)
            throws LogAbortException {
        TreeMap<String, ClassReader> found = new TreeMap<String, ClassReader>();

        mLog.debug("Find classes to include.");

        for (String s : mIncludeGlobs) {
            findGlobs(s, zipClasses, found);
        }
        for (String s : mDeriveFrom) {
            findClassesDerivingFrom(s, zipClasses, found);
        }

        return found;
    }


    /**
     * Uses ASM to find the class reader for the given FQCN class name.
     * If found, insert it in the in_out_found map.
     * Returns the class reader object.
     */
    ClassReader findClass(String className, Map<String, ClassReader> zipClasses,
            Map<String, ClassReader> inOutFound) throws LogAbortException {
        ClassReader classReader = zipClasses.get(className);
        if (classReader == null) {
            throw new LogAbortException("Class %s not found by ASM in %s",
                    className, mOsSourceJar);
        }

        inOutFound.put(className, classReader);
        return classReader;
    }

    /**
     * Insert in the inOutFound map all classes found in zipClasses that match the
     * given glob pattern.
     * <p/>
     * The glob pattern is not a regexp. It only accepts the "*" keyword to mean
     * "anything but a period". The "." and "$" characters match themselves.
     * The "**" keyword means everything including ".".
     * <p/>
     * Examples:
     * <ul>
     * <li>com.foo.* matches all classes in the package com.foo but NOT sub-packages.
     * <li>com.foo*.*$Event matches all internal Event classes in a com.foo*.* class.
     * </ul>
     */
    void findGlobs(String globPattern, Map<String, ClassReader> zipClasses,
            Map<String, ClassReader> inOutFound) throws LogAbortException {
        // transforms the glob pattern in a regexp:
        // - escape "." with "\."
        // - replace "*" by "[^.]*"
        // - escape "$" with "\$"
        // - add end-of-line match $
        globPattern = globPattern.replaceAll("\\$", "\\\\\\$");
        globPattern = globPattern.replaceAll("\\.", "\\\\.");
        // prevent ** from being altered by the next rule, then process the * rule and finally
        // the real ** rule (which is now @)
        globPattern = globPattern.replaceAll("\\*\\*", "@");
        globPattern = globPattern.replaceAll("\\*", "[^.]*");
        globPattern = globPattern.replaceAll("@", ".*");
        globPattern += "$";

        Pattern regexp = Pattern.compile(globPattern);

        for (Entry<String, ClassReader> entry : zipClasses.entrySet()) {
            String class_name = entry.getKey();
            if (regexp.matcher(class_name).matches()) {
                findClass(class_name, zipClasses, inOutFound);
            }
        }
    }

    /**
     * Checks all the classes defined in the JarClassName instance and uses BCEL to
     * determine if they are derived from the given FQCN super class name.
     * Inserts the super class and all the class objects found in the map.
     */
    void findClassesDerivingFrom(String super_name,
            Map<String, ClassReader> zipClasses,
            Map<String, ClassReader> inOutFound) {
        for (Entry<String, ClassReader> entry : zipClasses.entrySet()) {
            String className = entry.getKey();
            if (super_name.equals(className)) {
                continue;
            }
            ClassReader classReader = entry.getValue();
            ClassReader parent_cr = classReader;
            while (parent_cr != null) {
                String parent_name = internalToBinaryClassName(parent_cr.getSuperName());
                if (parent_name == null) {
                    // not found
                    break;
                } else if (super_name.equals(parent_name)) {
                    inOutFound.put(className, classReader);
                    break;
                }
                parent_cr = zipClasses.get(parent_name);
            }
        }
    }

    /**
     * Instantiates a new DependencyVisitor. Useful for unit tests.
     */
    DependencyVisitor getVisitor(Map<String, ClassReader> zipClasses,
            Map<String, ClassReader> inKeep,
            Map<String, ClassReader> outKeep,
            Map<String, ClassReader> inDeps,
            Map<String, ClassReader> outDeps) {
        return new DependencyVisitor(zipClasses, inKeep, outKeep, inDeps, outDeps);
    }

    /**
     * Finds all dependencies for all classes in keepClasses which are also
     * listed in zipClasses. Returns a map of all the dependencies found.
     */
    Map<String, ClassReader> findDeps(Map<String, ClassReader> zipClasses,
            Map<String, ClassReader> inOutKeepClasses) {

        TreeMap<String, ClassReader> deps = new TreeMap<String, ClassReader>();
        TreeMap<String, ClassReader> new_deps = new TreeMap<String, ClassReader>();
        TreeMap<String, ClassReader> new_keep = new TreeMap<String, ClassReader>();
        TreeMap<String, ClassReader> temp = new TreeMap<String, ClassReader>();

        DependencyVisitor visitor = getVisitor(zipClasses,
                inOutKeepClasses, new_keep,
                deps, new_deps);

        for (ClassReader cr : inOutKeepClasses.values()) {
            cr.accept(visitor, 0 /* flags */);
        }

        while (new_deps.size() > 0 || new_keep.size() > 0) {
            deps.putAll(new_deps);
            inOutKeepClasses.putAll(new_keep);

            temp.clear();
            temp.putAll(new_deps);
            temp.putAll(new_keep);
            new_deps.clear();
            new_keep.clear();
            mLog.debug("Found %1$d to keep, %2$d dependencies.",
                    inOutKeepClasses.size(), deps.size());

            for (ClassReader cr : temp.values()) {
                cr.accept(visitor, 0 /* flags */);
            }
        }

        mLog.info("Found %1$d classes to keep, %2$d class dependencies.",
                inOutKeepClasses.size(), deps.size());

        return deps;
    }



    // ----------------------------------

    /**
     * Visitor to collect all the type dependencies from a class.
     */
    public class DependencyVisitor
        implements ClassVisitor, FieldVisitor, MethodVisitor, SignatureVisitor, AnnotationVisitor {

        /** All classes found in the source JAR. */
        private final Map<String, ClassReader> mZipClasses;
        /** Classes from which dependencies are to be found. */
        private final Map<String, ClassReader> mInKeep;
        /** Dependencies already known. */
        private final Map<String, ClassReader> mInDeps;
        /** New dependencies found by this visitor. */
        private final Map<String, ClassReader> mOutDeps;
        /** New classes to keep as-is found by this visitor. */
        private final Map<String, ClassReader> mOutKeep;

        /**
         * Creates a new visitor that will find all the dependencies for the visited class.
         * Types which are already in the zipClasses, keepClasses or inDeps are not marked.
         * New dependencies are marked in outDeps.
         *
         * @param zipClasses All classes found in the source JAR.
         * @param inKeep Classes from which dependencies are to be found.
         * @param inDeps Dependencies already known.
         * @param outDeps New dependencies found by this visitor.
         */
        public DependencyVisitor(Map<String, ClassReader> zipClasses,
                Map<String, ClassReader> inKeep,
                Map<String, ClassReader> outKeep,
                Map<String,ClassReader> inDeps,
                Map<String,ClassReader> outDeps) {
            mZipClasses = zipClasses;
            mInKeep = inKeep;
            mOutKeep = outKeep;
            mInDeps = inDeps;
            mOutDeps = outDeps;
        }

        /**
         * Considers the given class name as a dependency.
         * If it does, add to the mOutDeps map.
         */
        public void considerName(String className) {
            if (className == null) {
                return;
            }

            className = internalToBinaryClassName(className);

            // exclude classes that have already been found
            if (mInKeep.containsKey(className) ||
                    mOutKeep.containsKey(className) ||
                    mInDeps.containsKey(className) ||
                    mOutDeps.containsKey(className)) {
                return;
            }

            // exclude classes that are not part of the JAR file being examined
            ClassReader cr = mZipClasses.get(className);
            if (cr == null) {
                return;
            }

            try {
                // exclude classes that are part of the default JRE (the one executing this program)
                if (getClass().getClassLoader().loadClass(className) != null) {
                    return;
                }
            } catch (ClassNotFoundException e) {
                // ignore
            }

            // accept this class:
            // - android classes are added to dependencies
            // - non-android classes are added to the list of classes to keep as-is (they don't need
            //   to be stubbed).
            if (className.indexOf("android") >= 0) {  // TODO make configurable
                mOutDeps.put(className, cr);
            } else {
                mOutKeep.put(className, cr);
            }
        }

        /**
         * Considers this array of names using considerName().
         */
        public void considerNames(String[] classNames) {
            if (classNames != null) {
                for (String className : classNames) {
                    considerName(className);
                }
            }
        }

        /**
         * Considers this signature or type signature by invoking the {@link SignatureVisitor}
         * on it.
         */
        public void considerSignature(String signature) {
            if (signature != null) {
                SignatureReader sr = new SignatureReader(signature);
                // SignatureReader.accept will call accessType so we don't really have
                // to differentiate where the signature comes from.
                sr.accept(this);
            }
        }

        /**
         * Considers this {@link Type}. For arrays, the element type is considered.
         * If the type is an object, it's internal name is considered.
         */
        public void considerType(Type t) {
            if (t != null) {
                if (t.getSort() == Type.ARRAY) {
                    t = t.getElementType();
                }
                if (t.getSort() == Type.OBJECT) {
                    considerName(t.getInternalName());
                }
            }
        }

        /**
         * Considers a descriptor string. The descriptor is converted to a {@link Type}
         * and then considerType() is invoked.
         */
        public void considerDesc(String desc) {
            if (desc != null) {
                try {
                    Type t = Type.getType(desc);
                    considerType(t);
                } catch (ArrayIndexOutOfBoundsException e) {
                    // ignore, not a valid type.
                }
            }
        }


        // ---------------------------------------------------
        // --- ClassVisitor, FieldVisitor
        // ---------------------------------------------------

        // Visits a class header
        public void visit(int version, int access, String name,
                String signature, String superName, String[] interfaces) {
            // signature is the signature of this class. May be null if the class is not a generic
            // one, and does not extend or implement generic classes or interfaces.

            if (signature != null) {
                considerSignature(signature);
            }

            // superName is the internal of name of the super class (see getInternalName).
            // For interfaces, the super class is Object. May be null but only for the Object class.
            considerName(superName);

            // interfaces is the internal names of the class's interfaces (see getInternalName).
            // May be null.
            considerNames(interfaces);
        }

        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            // desc is the class descriptor of the annotation class.
            considerDesc(desc);
            return this; // return this to visit annotion values
        }

        public void visitAttribute(Attribute attr) {
            // pass
        }

        // Visits the end of a class
        public void visitEnd() {
            // pass
        }

        public FieldVisitor visitField(int access, String name, String desc,
                String signature, Object value) {
            // desc is the field's descriptor (see Type).
            considerDesc(desc);

            // signature is the field's signature. May be null if the field's type does not use
            // generic types.
            considerSignature(signature);

            return this; // a visitor to visit field annotations and attributes
        }

        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            // name is the internal name of an inner class (see getInternalName).
            considerName(name);
        }

        public MethodVisitor visitMethod(int access, String name, String desc,
                String signature, String[] exceptions) {
            // desc is the method's descriptor (see Type).
            considerDesc(desc);
            // signature is the method's signature. May be null if the method parameters, return
            // type and exceptions do not use generic types.
            considerSignature(signature);

            return this; // returns this to visit the method
        }

        public void visitOuterClass(String owner, String name, String desc) {
            // pass
        }

        public void visitSource(String source, String debug) {
            // pass
        }


        // ---------------------------------------------------
        // --- MethodVisitor
        // ---------------------------------------------------

        public AnnotationVisitor visitAnnotationDefault() {
            return this; // returns this to visit the default value
        }


        public void visitCode() {
            // pass
        }

        // field instruction
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            // name is the field's name.
            considerName(name);
            // desc is the field's descriptor (see Type).
            considerDesc(desc);
        }

        public void visitFrame(int type, int local, Object[] local2, int stack, Object[] stack2) {
            // pass
        }

        public void visitIincInsn(int var, int increment) {
            // pass -- an IINC instruction
        }

        public void visitInsn(int opcode) {
            // pass -- a zero operand instruction
        }

        public void visitIntInsn(int opcode, int operand) {
            // pass -- a single int operand instruction
        }

        public void visitJumpInsn(int opcode, Label label) {
            // pass -- a jump instruction
        }

        public void visitLabel(Label label) {
            // pass -- a label target
        }

        // instruction to load a constant from the stack
        public void visitLdcInsn(Object cst) {
            if (cst instanceof Type) {
                considerType((Type) cst);
            }
        }

        public void visitLineNumber(int line, Label start) {
            // pass
        }

        public void visitLocalVariable(String name, String desc,
                String signature, Label start, Label end, int index) {
            // desc is the type descriptor of this local variable.
            considerDesc(desc);
            // signature is the type signature of this local variable. May be null if the local
            // variable type does not use generic types.
            considerSignature(signature);
        }

        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            // pass -- a lookup switch instruction
        }

        public void visitMaxs(int maxStack, int maxLocals) {
            // pass
        }

        // instruction that invokes a method
        public void visitMethodInsn(int opcode, String owner, String name, String desc) {

            // owner is the internal name of the method's owner class
            considerName(owner);
            // desc is the method's descriptor (see Type).
            considerDesc(desc);
        }

        // instruction multianewarray, whatever that is
        public void visitMultiANewArrayInsn(String desc, int dims) {

            // desc an array type descriptor.
            considerDesc(desc);
        }

        public AnnotationVisitor visitParameterAnnotation(int parameter, String desc,
                boolean visible) {
            // desc is the class descriptor of the annotation class.
            considerDesc(desc);
            return this; // return this to visit annotation values
        }

        public void visitTableSwitchInsn(int min, int max, Label dflt, Label[] labels) {
            // pass -- table switch instruction

        }

        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            // type is the internal name of the type of exceptions handled by the handler,
            // or null to catch any exceptions (for "finally" blocks).
            considerName(type);
        }

        // type instruction
        public void visitTypeInsn(int opcode, String type) {
            // type is the operand of the instruction to be visited. This operand must be the
            // internal name of an object or array class.
            considerName(type);
        }

        public void visitVarInsn(int opcode, int var) {
            // pass -- local variable instruction
        }


        // ---------------------------------------------------
        // --- SignatureVisitor
        // ---------------------------------------------------

        private String mCurrentSignatureClass = null;

        // Starts the visit of a signature corresponding to a class or interface type
        public void visitClassType(String name) {
            mCurrentSignatureClass = name;
            considerName(name);
        }

        // Visits an inner class
        public void visitInnerClassType(String name) {
            if (mCurrentSignatureClass != null) {
                mCurrentSignatureClass += "$" + name;
                considerName(mCurrentSignatureClass);
            }
        }

        public SignatureVisitor visitArrayType() {
            return this; // returns this to visit the signature of the array element type
        }

        public void visitBaseType(char descriptor) {
            // pass -- a primitive type, ignored
        }

        public SignatureVisitor visitClassBound() {
            return this; // returns this to visit the signature of the class bound
        }

        public SignatureVisitor visitExceptionType() {
            return this; // return this to visit the signature of the exception type.
        }

        public void visitFormalTypeParameter(String name) {
            // pass
        }

        public SignatureVisitor visitInterface() {
            return this; // returns this to visit the signature of the interface type
        }

        public SignatureVisitor visitInterfaceBound() {
            return this; // returns this to visit the signature of the interface bound
        }

        public SignatureVisitor visitParameterType() {
            return this; // returns this to visit the signature of the parameter type
        }

        public SignatureVisitor visitReturnType() {
            return this; // returns this to visit the signature of the return type
        }

        public SignatureVisitor visitSuperclass() {
            return this; // returns this to visit the signature of the super class type
        }

        public SignatureVisitor visitTypeArgument(char wildcard) {
            return this; // returns this to visit the signature of the type argument
        }

        public void visitTypeVariable(String name) {
            // pass
        }

        public void visitTypeArgument() {
            // pass
        }


        // ---------------------------------------------------
        // --- AnnotationVisitor
        // ---------------------------------------------------


        // Visits a primitive value of an annotation
        public void visit(String name, Object value) {
            // value is the actual value, whose type must be Byte, Boolean, Character, Short,
            // Integer, Long, Float, Double, String or Type
            if (value instanceof Type) {
                considerType((Type) value);
            }
        }

        public AnnotationVisitor visitAnnotation(String name, String desc) {
            // desc is the class descriptor of the nested annotation class.
            considerDesc(desc);
            return this; // returns this to visit the actual nested annotation value
        }

        public AnnotationVisitor visitArray(String name) {
            return this; // returns this to visit the actual array value elements
        }

        public void visitEnum(String name, String desc, String value) {
            // desc is the class descriptor of the enumeration class.
            considerDesc(desc);
        }

    }
}
