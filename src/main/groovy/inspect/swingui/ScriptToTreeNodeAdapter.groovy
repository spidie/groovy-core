/*
 * Copyright 2003-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package groovy.inspect.swingui


import groovy.inspect.swingui.TreeNodeWithProperties
import groovy.text.GStringTemplateEngine
import groovy.text.Template
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeNode
import org.codehaus.groovy.GroovyBugError
import org.codehaus.groovy.classgen.BytecodeExpression
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilationUnit.PrimaryClassNodeOperation
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.*

/**
 * This class controls the conversion from a Groovy script as a String into
 * a Swing JTree representation of the AST in that script. The script itself
 * will be a TreeNode, and each class in the script will be a tree node. The
 * conversion creates tree nodes for any concrete class found within an AST
 * visitor. So, if a TreeNode should be shown once for each ASTNode and the parent
 * types will not appear as nodes. Custom subclasses of expression types will
 * not appear in the tree.
 *
 * The String label of a TreeNode is defined by classname in AstBrowserProperties.properties.
 *
 * @author Hamlet D'Arcy 
 */
class ScriptToTreeNodeAdapter {

    def static Properties classNameToStringForm

    static {
        URL url =  ClassLoader.getSystemResource("groovy/inspect/swingui/AstBrowserProperties.groovy");
        File rootProperties = new File(URLDecoder.decode(url.getFile(), "UTF-8"));
        if (rootProperties.exists()) {
            def config = new ConfigSlurper().parse(rootProperties.toURL())
            classNameToStringForm = config.toProperties()
        } else {
            classNameToStringForm = new Properties()
        }

        String home = System.getProperty("user.home")
        if (home) {
            File userFile = new File(home + File.separator + ".groovy/AstBrowserProperties.groovy")
            if (userFile.exists()) {
                def config = new ConfigSlurper().parse(userFile.toURL())
                classNameToStringForm.putAll(config.toProperties())
            }
        }
    }



    /**
    * Performs the conversion from script to TreeNode.
     *
     * @param script
     *      a Groovy script in String form
     * @param compilePhase
     *      the int based CompilePhase to compile it to.
    */
    public TreeNode compile(String script, int compilePhase) {
        def scriptName = "script" + System.currentTimeMillis() + ".groovy"
        GroovyClassLoader classLoader = new GroovyClassLoader()
        GroovyCodeSource codeSource = new GroovyCodeSource(script, scriptName, "/groovy/script")
        CompilationUnit cu = new CompilationUnit(CompilerConfiguration.DEFAULT, codeSource.codeSource, classLoader)
        TreeNodeBuildingNodeOperation operation = new TreeNodeBuildingNodeOperation(this)
        cu.addPhaseOperation(operation, compilePhase)
        cu.addSource(codeSource.getName(), codeSource.getInputStream());
        cu.compile(compilePhase)
        return operation.root
    }

    TreeNode make(ASTNode node) {
        new TreeNodeWithProperties(getStringForm(node), getPropertyTable(node))
    }

    /**
     * Creates the property table for the node so that the properties view can display nicely.
     */
    private List<List<String>> getPropertyTable(ASTNode node) {
        node.metaClass.properties?.
            findAll { it.getter }?.
            collect {
                def name = it.name.toString()
                def value
                try {
                    value = it.getProperty(node).toString()
                } catch (GroovyBugError reflectionArtefact) {
                    // compiler throws error is it thinks a field is being accessed
                    // before it is set under certain conditions. It wasn't designed
                    // to be walked reflectively like this.
                    value = null
                }
                def type = it.type.simpleName.toString()
                [name, value, type]
            }?.
            sort() { it[0] }
    }

    /**
     * Handles the property file templating for node types.
     */
    private String getStringForm(ASTNode node) {
		if (classNameToStringForm[node.class.name]) {
			GStringTemplateEngine engine = new GStringTemplateEngine()
			def script = classNameToStringForm[node.class.name]
			Template template = engine.createTemplate(script)
			Writable writable = template.make([expression: node])
			StringWriter result = new StringWriter()
			writable.writeTo(result)
			result.toString()
		} else {
			node.class.simpleName
		}
	}
}

/**
 * This Node Operation builds up a root tree node for the viewer.
 * @author Hamlet D'Arcy
 */
private class TreeNodeBuildingNodeOperation extends PrimaryClassNodeOperation {

    final def root = new DefaultMutableTreeNode("root")
    final def sourceCollected = new AtomicBoolean(false)
    final ScriptToTreeNodeAdapter adapter

    def TreeNodeBuildingNodeOperation(adapter) {
        if (!adapter) throw new IllegalArgumentException("Null: adapter")
        this.adapter = adapter;
    }

    public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {

        // module node
        if (!sourceCollected.getAndSet(true)) {
            // display the source unit AST
            ModuleNode ast = source.getAST()
            TreeNodeBuildingVisitor visitor = new TreeNodeBuildingVisitor(adapter)
            ast.getStatementBlock().visit(visitor)
            if (visitor.currentNode) root.add(visitor.currentNode)
        }

        def child = adapter.make(classNode)
        root.add(child)

        //constructors
        def allCtors = new DefaultMutableTreeNode("Constructors")
        if (classNode.constructors) child.add(allCtors)
        classNode.constructors?.each { ConstructorNode ctorNode ->

            def ggrandchild = adapter.make(ctorNode)
            allCtors.add(ggrandchild)
            TreeNodeBuildingVisitor visitor = new TreeNodeBuildingVisitor(adapter)
            if (ctorNode.code) {
                ctorNode.code.visit(visitor)
                if (visitor.currentNode) ggrandchild.add(visitor.currentNode)
            }
        }


        // methods
        def allMethods = new DefaultMutableTreeNode("Methods")
        if (classNode.methodsList) child.add(allMethods)
        classNode.methodsList?.each { MethodNode methodNode ->

            def ggrandchild = adapter.make(methodNode)
            allMethods.add(ggrandchild)
            TreeNodeBuildingVisitor visitor = new TreeNodeBuildingVisitor(adapter)
            if (methodNode.code) {
                methodNode.code.visit(visitor)
                if (visitor.currentNode) ggrandchild.add(visitor.currentNode)
            }
        }

        //fields
        def allFields = new DefaultMutableTreeNode("Fields")
        if (classNode.fields) child.add(allFields)
        classNode.fields?.each { FieldNode fieldNode ->
            def ggrandchild = adapter.make(fieldNode)
            allFields.add(ggrandchild)
            TreeNodeBuildingVisitor visitor = new TreeNodeBuildingVisitor(adapter)
            if (fieldNode.initialValueExpression) {
                fieldNode.initialValueExpression.visit(visitor)
                if (visitor.currentNode) ggrandchild.add(visitor.currentNode)
            }
        }

        //properties
        def allProperties = new DefaultMutableTreeNode("Properties")
        if (classNode.properties) child.add(allProperties)
        classNode.properties?.each { PropertyNode propertyNode ->
            def ggrandchild = adapter.make(propertyNode)
            allProperties.add(ggrandchild)
            TreeNodeBuildingVisitor visitor = new TreeNodeBuildingVisitor(adapter)
            if (propertyNode.field?.initialValueExpression) {
                propertyNode.field.initialValueExpression.visit(visitor)
                ggrandchild.add(visitor.currentNode)
            }
        }


        //annotations
        def allAnnotations = new DefaultMutableTreeNode("Annotations")
        if (classNode.annotations) child.add(allAnnotations)
        classNode.annotations?.each { AnnotationNode annotationNode ->
            def ggrandchild = adapter.make(annotationNode)
            allAnnotations.add(ggrandchild)
        }
    }
}

/**
* This AST visitor builds up a TreeNode.
 *
 * @author Hamlet D'Arcy
*/
private class TreeNodeBuildingVisitor extends CodeVisitorSupport {

    DefaultMutableTreeNode currentNode
    private adapter

    /**
     * Creates the visitor. A file named AstBrowserProperties.groovy is located which is
     * a property files the describes how to represent ASTNode types as Strings.
     */
	private TreeNodeBuildingVisitor(adapter) {
        if (!adapter) throw new IllegalArgumentException("Null: adapter")
        this.adapter = adapter;
    }

    /**
    * This method looks at the AST node and decides how to represent it in a TreeNode, then it
     * continues walking the tree. If the node and the expectedSubclass are not exactly the same
     * Class object then the node is not added to the tree. This is to eliminate seeing duplicate
     * nodes, for instance seeing an ArgumentListExpression and a TupleExpression in the tree, when
     * an ArgumentList is-a Tuple.
    */
    private DefaultMutableTreeNode addNode(ASTNode node, Class expectedSubclass, Closure superMethod) {

        if (expectedSubclass.getName() == node.getClass().getName()) {
            DefaultMutableTreeNode parent
            if (currentNode == null) {
                parent = adapter.make(node)
            } else {
                parent = currentNode;
            }

            currentNode = adapter.make(node)

            parent.add(currentNode)
            currentNode.parent = parent
            superMethod.call(node)
            currentNode = parent
        } else {
            superMethod.call(node)
        }
    }

    public void visitBlockStatement(BlockStatement node) {
        addNode(node, BlockStatement, { super.visitBlockStatement(it) });
    }

    public void visitForLoop(ForStatement node) {
        addNode(node, ForStatement, { super.visitForLoop(it) });
    }

    public void visitWhileLoop(WhileStatement node) {
        addNode(node, WhileStatement, { super.visitWhileLoop(it) });
    }

    public void visitDoWhileLoop(DoWhileStatement node) {
        addNode(node, DoWhileStatement, { super.visitDoWhileLoop(it) });
    }

    public void visitIfElse(IfStatement node) {
        addNode(node, IfStatement, { super.visitIfElse(it) });
    }

    public void visitExpressionStatement(ExpressionStatement node) {
        addNode(node, ExpressionStatement, { super.visitExpressionStatement(it) });
    }

    public void visitReturnStatement(ReturnStatement node) {
        addNode(node, ReturnStatement, { super.visitReturnStatement(it) });
    }

    public void visitAssertStatement(AssertStatement node) {
        addNode(node, AssertStatement, { super.visitAssertStatement(it) });
    }

    public void visitTryCatchFinally(TryCatchStatement node) {
        addNode(node, TryCatchStatement, { super.visitTryCatchFinally(it) });
    }

    public void visitSwitch(SwitchStatement node) {
        addNode(node, SwitchStatement, { super.visitSwitch(it) });
    }

    public void visitCaseStatement(CaseStatement node) {
        addNode(node, CaseStatement, { super.visitCaseStatement(it) });
    }

    public void visitBreakStatement(BreakStatement node) {
        addNode(node, BreakStatement, { super.visitBreakStatement(it) });
    }

    public void visitContinueStatement(ContinueStatement node) {
        addNode(node, ContinueStatement, { super.visitContinueStatement(it) });
    }

    public void visitSynchronizedStatement(SynchronizedStatement node) {
        addNode(node, SynchronizedStatement, { super.visitSynchronizedStatement(it) });
    }

    public void visitThrowStatement(ThrowStatement node) {
        addNode(node, ThrowStatement, { super.visitThrowStatement(it) });
    }

    public void visitMethodCallExpression(MethodCallExpression node) {
        addNode(node, MethodCallExpression, { super.visitMethodCallExpression(it) });
    }

    public void visitStaticMethodCallExpression(StaticMethodCallExpression node) {
        addNode(node, StaticMethodCallExpression, { super.visitStaticMethodCallExpression(it) });
    }

    public void visitConstructorCallExpression(ConstructorCallExpression node) {
        addNode(node, ConstructorCallExpression, { super.visitConstructorCallExpression(it) });
    }

    public void visitBinaryExpression(BinaryExpression node) {
        addNode(node, BinaryExpression, { super.visitBinaryExpression(it) });
    }

    public void visitTernaryExpression(TernaryExpression node) {
        addNode(node, TernaryExpression, { super.visitTernaryExpression(it) });
    }

    public void visitShortTernaryExpression(ElvisOperatorExpression node) {
        addNode(node, ElvisOperatorExpression, { super.visitShortTernaryExpression(it) });
    }

    public void visitPostfixExpression(PostfixExpression node) {
        addNode(node, PostfixExpression, { super.visitPostfixExpression(it) });
    }

    public void visitPrefixExpression(PrefixExpression node) {
        addNode(node, PrefixExpression, { super.visitPrefixExpression(it) });
    }

    public void visitBooleanExpression(BooleanExpression node) {
        addNode(node, BooleanExpression, { super.visitBooleanExpression(it) });
    }

    public void visitNotExpression(NotExpression node) {
        addNode(node, NotExpression, { super.visitNotExpression(it) });
    }

    public void visitClosureExpression(ClosureExpression node) {
        addNode(node, ClosureExpression, { super.visitClosureExpression(it) });
    }

    public void visitTupleExpression(TupleExpression node) {
        addNode(node, TupleExpression, { super.visitTupleExpression(it) });
    }

    public void visitListExpression(ListExpression node) {
        addNode(node, ListExpression, { super.visitListExpression(it) });
    }

    public void visitArrayExpression(ArrayExpression node) {
        addNode(node, ArrayExpression, { super.visitArrayExpression(it) });
    }

    public void visitMapExpression(MapExpression node) {
        addNode(node, MapExpression, { super.visitMapExpression(it) });
    }

    public void visitMapEntryExpression(MapEntryExpression node) {
        addNode(node, MapEntryExpression, { super.visitMapEntryExpression(it) });
    }

    public void visitRangeExpression(RangeExpression node) {
        addNode(node, RangeExpression, { super.visitRangeExpression(it) });
    }

    public void visitSpreadExpression(SpreadExpression node) {
        addNode(node, SpreadExpression, { super.visitSpreadExpression(it) });
    }

    public void visitSpreadMapExpression(SpreadMapExpression node) {
        addNode(node, SpreadMapExpression, { super.visitSpreadMapExpression(it) });
    }

    public void visitMethodPointerExpression(MethodPointerExpression node) {
        addNode(node, MethodPointerExpression, { super.visitMethodPointerExpression(it) });
    }

    public void visitUnaryMinusExpression(UnaryMinusExpression node) {
        addNode(node, UnaryMinusExpression, { super.visitUnaryMinusExpression(it) });
    }

    public void visitUnaryPlusExpression(UnaryPlusExpression node) {
        addNode(node, UnaryPlusExpression, { super.visitUnaryPlusExpression(it) });
    }

    public void visitBitwiseNegationExpression(BitwiseNegationExpression node) {
        addNode(node, BitwiseNegationExpression, { super.visitBitwiseNegationExpression(it) });
    }

    public void visitCastExpression(CastExpression node) {
        addNode(node, CastExpression, { super.visitCastExpression(it) });
    }

    public void visitConstantExpression(ConstantExpression node) {
        addNode(node, ConstantExpression, { super.visitConstantExpression(it) });
    }

    public void visitClassExpression(ClassExpression node) {
        addNode(node, ClassExpression, { super.visitClassExpression(it) });
    }

    public void visitVariableExpression(VariableExpression node) {
        addNode(node, VariableExpression, { super.visitVariableExpression(it) });
    }

    public void visitDeclarationExpression(DeclarationExpression node) {
        addNode(node, DeclarationExpression, { super.visitDeclarationExpression(it) });
    }

    public void visitPropertyExpression(PropertyExpression node) {
        addNode(node, PropertyExpression, { super.visitPropertyExpression(it) });
    }

    public void visitAttributeExpression(AttributeExpression node) {
        addNode(node, AttributeExpression, { super.visitAttributeExpression(it) });
    }

    public void visitFieldExpression(FieldExpression node) {
        addNode(node, FieldExpression, { super.visitFieldExpression(it) });
    }

    public void visitRegexExpression(RegexExpression node) {
        addNode(node, RegexExpression, { super.visitRegexExpression(it) });
    }

    public void visitGStringExpression(GStringExpression node) {
        addNode(node, GStringExpression, { super.visitGStringExpression(it) });
    }

    public void visitCatchStatement(CatchStatement node) {
        addNode(node, CatchStatement, { super.visitCatchStatement(it) });
    }

    public void visitArgumentlistExpression(ArgumentListExpression node) {
        addNode(node, ArgumentListExpression, { super.visitArgumentlistExpression(it) });
    }

    public void visitClosureListExpression(ClosureListExpression node) {
        addNode(node, ClosureListExpression, { super.visitClosureListExpression(it) });
    }

    public void visitBytecodeExpression(BytecodeExpression node) {
        addNode(node, BytecodeExpression, { super.visitBytecodeExpression(it) });
    }
}