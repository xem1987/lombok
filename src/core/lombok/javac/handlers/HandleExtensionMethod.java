/*
 * Copyright (C) 2012 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.javac.handlers;

import static com.sun.tools.javac.code.Flags.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;
import static lombok.javac.handlers.JavacResolver.*;

import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.ElementKind;

import lombok.core.AnnotationValues;
import lombok.core.HandlerPriority;
import lombok.experimental.ExtensionMethod;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;

import org.mangosdk.spi.ProviderFor;

import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.Type.ErrorType;
import com.sun.tools.javac.code.Type.ForAll;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;

/**
 * Handles the {@link ExtensionMethod} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class)
@HandlerPriority(66560) // 2^16 + 2^10; we must run AFTER HandleVal which is at 2^16
public class HandleExtensionMethod extends JavacAnnotationHandler<ExtensionMethod> {
	@Override
	public void handle(final AnnotationValues<ExtensionMethod> annotation, final JCAnnotation source, final JavacNode annotationNode) {
		deleteAnnotationIfNeccessary(annotationNode, ExtensionMethod.class);
		JavacNode typeNode = annotationNode.up();
		boolean isClassOrEnum = isClassOrEnum(typeNode);
		
		if (!isClassOrEnum) {
			annotationNode.addError("@ExtensionMethod can only be used on a class or an enum");
			return;
		}
		
		boolean suppressBaseMethods = annotation.getInstance().suppressBaseMethods();
		
		List<Object> extensionProviders = annotation.getActualExpressions("value");
		if (extensionProviders.isEmpty()) {
			annotationNode.addError(String.format("@%s has no effect since no extension types were specified.", ExtensionMethod.class.getName()));
			return;
		}
		final List<Extension> extensions = getExtensions(annotationNode, extensionProviders);
		if (extensions.isEmpty()) return;
		
		new ExtensionMethodReplaceVisitor(annotationNode, extensions, suppressBaseMethods).replace();
		
		annotationNode.rebuild();
	}


	private List<Extension> getExtensions(final JavacNode typeNode, final List<Object> extensionProviders) {
		List<Extension> extensions = new ArrayList<Extension>();
		for (Object extensionProvider : extensionProviders) {
			if (!(extensionProvider instanceof JCFieldAccess)) continue;
			JCFieldAccess provider = (JCFieldAccess) extensionProvider;
			if (!("class".equals(provider.name.toString()))) continue;
			Type providerType = CLASS.resolveMember(typeNode, provider.selected);
			if (providerType == null) continue;
			if ((providerType.tsym.flags() & (INTERFACE | ANNOTATION)) != 0) continue;
			
			extensions.add(getExtension(typeNode, (ClassType) providerType));	
		}
		return extensions;
	}
	
	private Extension getExtension(final JavacNode typeNode, final ClassType extensionMethodProviderType) {
		List<MethodSymbol> extensionMethods = new ArrayList<MethodSymbol>();
		TypeSymbol tsym = extensionMethodProviderType.asElement();
		if (tsym != null) for (Symbol member : tsym.getEnclosedElements()) {
			if (member.getKind() != ElementKind.METHOD) continue;
			MethodSymbol method = (MethodSymbol) member;
			if ((method.flags() & (STATIC | PUBLIC)) == 0) continue;
			if (method.params().isEmpty()) continue;
			extensionMethods.add(method);
		}
		return new Extension(extensionMethods, tsym);
	}
	
	private static class Extension {
		final List<MethodSymbol> extensionMethods;
		final TypeSymbol extensionProvider;
		
		public Extension(List<MethodSymbol> extensionMethods, TypeSymbol extensionProvider) {
			this.extensionMethods = extensionMethods;
			this.extensionProvider = extensionProvider;
		}
	}
	
	private static class ExtensionMethodReplaceVisitor extends TreeScanner<Void, Void> {
		final JavacNode annotationNode;
		final List<Extension> extensions;
		final boolean suppressBaseMethods;
		
		public ExtensionMethodReplaceVisitor(JavacNode annotationNode, List<Extension> extensions, boolean suppressBaseMethods) {
			this.annotationNode = annotationNode;
			this.extensions = extensions;
			this.suppressBaseMethods = suppressBaseMethods;
		}
		
		public void replace() {
			annotationNode.up().get().accept(this, null);
		}
		
		@Override
		public Void visitMethodInvocation(final MethodInvocationTree tree, final Void p) {
			handleMethodCall((JCMethodInvocation) tree);
			return super.visitMethodInvocation(tree, p);
		}

		private void handleMethodCall(final JCMethodInvocation methodCall) {
			JavacNode methodCallNode = annotationNode.getAst().get(methodCall);
			
			JavacNode surroundingType = upToTypeNode(methodCallNode);
			
			TypeSymbol surroundingTypeSymbol = ((JCClassDecl)surroundingType.get()).sym;
			JCExpression receiver = receiverOf(methodCall);
			String methodName = methodNameOf(methodCall);
			
			if ("this".equals(methodName) || "super".equals(methodName)) return;
			Type resolvedMethodCall = CLASS_AND_METHOD.resolveMember(methodCallNode, methodCall);
			if (resolvedMethodCall == null) return;
			if (!suppressBaseMethods && !(resolvedMethodCall instanceof ErrorType)) return;
			Type receiverType = CLASS_AND_METHOD.resolveMember(methodCallNode, receiver);
			if (receiverType == null) return;
			if (receiverType.tsym.toString().endsWith(receiver.toString())) return;
			
			Types types = Types.instance(annotationNode.getContext());
			for (Extension extension : extensions) {
				TypeSymbol extensionProvider = extension.extensionProvider;
				if (surroundingTypeSymbol == extensionProvider) continue;
				for (MethodSymbol extensionMethod : extension.extensionMethods) {
					if (!methodName.equals(extensionMethod.name.toString())) continue;
					Type extensionMethodType = extensionMethod.type;
					if (!MethodType.class.isInstance(extensionMethodType) && !ForAll.class.isInstance(extensionMethodType)) continue;
					Type firstArgType = types.erasure(extensionMethodType.asMethodType().argtypes.get(0));
					if (!types.isAssignable(receiverType, firstArgType)) continue;
					methodCall.args = methodCall.args.prepend(receiver);
					methodCall.meth = chainDotsString(annotationNode, extensionProvider.toString() + "." + methodName);
					return;
				}
			}
		}
		
		private String methodNameOf(final JCMethodInvocation methodCall) {
			if (methodCall.meth instanceof JCIdent) {
				return ((JCIdent) methodCall.meth).name.toString();
			} else {
				return ((JCFieldAccess) methodCall.meth).name.toString();
			}
		}
		
		private JCExpression receiverOf(final JCMethodInvocation methodCall) {
			if (methodCall.meth instanceof JCIdent) {
				return annotationNode.getTreeMaker().Ident(annotationNode.toName("this"));
			} else {
				return ((JCFieldAccess) methodCall.meth).selected;
			}
		}
	}
}