/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.cli.impl;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.jboss.as.cli.CommandContext;
import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.CommandLineCompleter;
import org.jboss.as.cli.Util;
import org.jboss.as.cli.handlers.DefaultFilenameTabCompleter;
import org.jboss.as.cli.handlers.WindowsFilenameTabCompleter;
import org.jboss.as.cli.operation.OperationRequestAddress;
import org.jboss.as.cli.operation.impl.DefaultOperationRequestAddress;
import org.jboss.as.cli.parsing.CharacterHandler;
import org.jboss.as.cli.parsing.DefaultParsingState;
import org.jboss.as.cli.parsing.EscapeCharacterState;
import org.jboss.as.cli.parsing.GlobalCharacterHandlers;
import org.jboss.as.cli.parsing.ParsingContext;
import org.jboss.as.cli.parsing.ParsingStateCallbackHandler;
import org.jboss.as.cli.parsing.StateParser;
import org.jboss.as.cli.parsing.WordCharacterHandler;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * @author Alexey Loubyansky
 *
 */
public class ValueTypeCompleter implements CommandLineCompleter {

    /**
     * Instance is the Model of the parsed Value. It contains the tree of
     * instance references.
     */
    private abstract static class Instance {
        /* A property owned by an Instance.
         Some Property can have null name e.g.: Instance contained inside a List.
        */
        static class Property {
            private String name;
            private Instance value;
        }

        // An instance is contained in a Parent.
        private final Instance parent;
        private final List<Property> properties = new ArrayList<>();
        protected Property current;
        // An instance is compete if the terminal char has been seen.
        // Applies to List and Complex.
        private boolean complete;

        // The type description of the instance.
        protected ModelNode type;

        static Instance newInstance(Instance parent, String c) {
            switch (c) {
                case "[": {
                    return new ListInstance(parent);
                }
                case "{": {
                    return new ComplexInstance(parent);
                }
                default: {
                    return new SimpleInstance(parent, c);
                }
            }
        }

        Instance(Instance parent) {
            this.parent = parent;
        }

        ModelNode getType() {
            if (type == null) {
                return null;
            }
            if (type.has(Util.VALUE_TYPE)) {
                return type.get(Util.VALUE_TYPE);
            }
            return type;
        }

        abstract boolean isCompliantType(ModelNode t);

        Instance setComplete(char c) {
            this.complete = isTerminalChar(c);
            if (complete) {
                return parent == null ? this : parent;
            } else {
                return this;
            }
        }

        abstract boolean isTerminalChar(char c);

        boolean isComplete() {
            return complete;
        }

        void newProperty() {
            current = new Property();
            properties.add(current);
        }

        public Property getLastProperty() {
            if (properties.isEmpty()) {
                return null;
            }
            return properties.get(properties.size() - 1);
        }

        public void endProperty(String content) {
            if (current.value == null) {
                current.value = new SimpleInstance(this, content);
            }
        }

        private Instance newPropertyValue(String c) throws CommandFormatException {
            if (current == null) {
                throw new CommandFormatException("Invalid syntax");
            }
            current.value = Instance.newInstance(this, c);
            // Associates the type to the instance.
            // This depends on the nature of the current instance
            current.value.type = retrieveType();
            return current.value;
        }

        abstract ModelNode retrieveType();

        private void setPropertyName(String name) throws CommandFormatException {
            if (current == null) {
                throw new CommandFormatException("Invalid syntax");
            }
            current.name = name;
        }

        public String asString() {
            return null;
        }

        private boolean contains(String p) {
            boolean found = false;
            for (Property prop : properties) {
                if (prop.name != null && prop.name.equals(p)
                        && prop.value != null) {
                    found = true;
                    break;
                }
            }
            return found;
        }
    }

    private static class ListInstance extends Instance {

        public ListInstance(Instance parent) {
            super(parent);
        }

        @Override
        boolean isTerminalChar(char c) {
            return c == ']';
        }

        @Override
        boolean isCompliantType(ModelNode t) {
            return ValueTypeCompleter.typeEquals(t, ModelType.LIST);
        }

        @Override
        ModelNode retrieveType() {
            if (type.has(Util.VALUE_TYPE)) {
                return type.get(Util.VALUE_TYPE);
            }
            return null;
        }

    }

    private static class ComplexInstance extends Instance {

        public ComplexInstance(Instance parent) {
            super(parent);
        }

        @Override
        boolean isTerminalChar(char c) {
            return c == '}';
        }

        @Override
        public void endProperty(String content) {
            // Last property name is null then this is a name.
            if (current.name == null) {
                current.name = content;
                return;
            }
            super.endProperty(content);
        }

        @Override
        boolean isCompliantType(ModelNode t) {
            return ValueTypeCompleter.typeEquals(t, ModelType.OBJECT);
        }

        @Override
        ModelNode retrieveType() {
            if (current.name != null) {
                if (type.has(current.name)) {
                    return type.get(current.name);
                } else if (type.has(Util.VALUE_TYPE)) {
                    ModelNode vt = type.get(Util.VALUE_TYPE);
                    if (vt.has(current.name)) {
                        return vt.get(current.name);
                    }
                }
            }
            return null;
        }
    }

    private static class SimpleInstance extends Instance {

        private final String value;

        public SimpleInstance(Instance parent, String value) {
            super(parent);
            this.value = value;
        }

        @Override
        public String asString() {
            return value;
        }

        @Override
        boolean isCompliantType(ModelNode t) {
            return !ValueTypeCompleter.typeEquals(t, ModelType.OBJECT)
                    && !ValueTypeCompleter.typeEquals(t, ModelType.LIST);
        }

        @Override
        boolean isTerminalChar(char c) {
            return false;
        }

        @Override
        ModelNode retrieveType() {
            return null;
        }
    }

    private static final List<ModelNode> BOOLEAN_LIST = new ArrayList<ModelNode>(2);
    static {
        BOOLEAN_LIST.add(new ModelNode(Boolean.FALSE));
        BOOLEAN_LIST.add(new ModelNode(Boolean.TRUE));
    }

    private final ModelNode propDescr;
    private Instance currentInstance;
    private CommandContext ctx;
    private final OperationRequestAddress address;

    public ValueTypeCompleter(ModelNode propDescr) {
        this(propDescr, new DefaultOperationRequestAddress());
    }

    public ValueTypeCompleter(ModelNode propDescr, OperationRequestAddress address) {
        if(propDescr == null || !propDescr.isDefined()) {
            throw new IllegalArgumentException("property description is null or undefined.");
        }
        this.propDescr = propDescr;
        this.address = address;
    }

    @Override
    public int complete(CommandContext ctx, String buffer, int cursor, List<String> candidates) {
        // The context is used deep down the completion processes by concrete class implementing
        // public interfaces.
        this.ctx = ctx;
        /*        int nextCharIndex = 0;
        while (nextCharIndex < buffer.length()) {
            if (!Character.isWhitespace(buffer.charAt(nextCharIndex))) {
                break;
            }
            ++nextCharIndex;
        }
         */
        final ValueTypeCallbackHandler handler;
        try {
            handler = parse(buffer);
        } catch (CommandFormatException e) {
            // TODO add logging here
            return -1;
        }
        final Collection<String> foundCandidates = handler.getCandidates(propDescr);
        if(foundCandidates.isEmpty()) {
            return -1;
        }
        candidates.addAll(foundCandidates);
        return handler.getCompletionIndex();
    }

    protected ValueTypeCallbackHandler parse(String line) throws CommandFormatException {
        final ValueTypeCallbackHandler valueTypeHandler = new ValueTypeCallbackHandler(false);
        StateParser.parse(line, valueTypeHandler, InitialValueState.INSTANCE);
        return valueTypeHandler;
    }

    private static boolean typeEquals(ModelNode mn, ModelType type) {
        ModelType mt;
        try {
            mt = mn.asType();
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return mt.equals(type);
    }

    private static boolean isObject(ModelNode mn) {
        ModelType mt;
        try {
            mt = mn.asType();
        } catch (IllegalArgumentException ex) {
            return true;
        }
        return false;
    }

    private final class ValueTypeCallbackHandler implements ParsingStateCallbackHandler {

        private static final String offsetStep = "  ";

        private final boolean logging;
        private int offset;

        private StringBuilder propBuf = new StringBuilder();
        private String lastEnteredState;
        private int lastStateIndex;
        private char lastStateChar;
        private int valLength;

//        ValueTypeCallbackHandler() {
//            this(false);
//        }
        ValueTypeCallbackHandler(boolean logging) {
            this.logging = logging;
        }

        private List<String> getCandidatesFromMetadata(ModelNode propType,
                String path) {
            List<String> candidates = null;
            if (propType.has(Util.FILESYSTEM_PATH)
                    && propType.get(Util.FILESYSTEM_PATH).asBoolean()) {
                CommandLineCompleter completer = Util.isWindows()
                        ? new WindowsFilenameTabCompleter(ctx)
                        : new DefaultFilenameTabCompleter(ctx);
                candidates = new ArrayList<>();
                completer.complete(ctx, path, offset, candidates);
                // candidates only contain the last part of a path.
                // We need to keep the radical and do the replacement after it
                // valLength is used when computing the replacement index.
                // On Windows, the '\' is a state, so the index is already at the end of
                // the stream.
                valLength = path.lastIndexOf(File.separator) + 1;
                if (Util.isWindows()) {
                    int i = path.lastIndexOf(File.separator);
                    if (i >= 0) { // Take into account escape char
                        valLength = 2;
                    }
                    // Must escape separator on Windows.
                    if (candidates.size() == 1) {
                        String candidate = candidates.get(0);
                        if (candidate.endsWith(File.separator)) {
                            candidate = candidate + File.separator;
                        }
                        candidates.set(0, candidate);
                    }
                }
            } else if (propType.has(Util.RELATIVE_TO)
                    && propType.get(Util.RELATIVE_TO).asBoolean()) {
                DeploymentItemCompleter completer
                        = new DeploymentItemCompleter(address);
                candidates = new ArrayList<>();
                valLength = completer.complete(ctx, path, offset, candidates);
            }
            return candidates;
        }

        public int getCompletionIndex() {
            //System.out.println("\ngetCompletionIndex: " + lastStateChar + " " + lastStateIndex);
            switch(lastStateChar) {
                case '{':
                case '}':
                case '[':
                case ']':
                case '=':
                case ',':
                    return lastStateIndex + 1;
            }
            // Some value completer compute an offset between the lastStateIndex
            // and the returned candidates (e.g.: file paths completion
            return lastStateIndex + valLength;
        }

        public Collection<String> getCandidates(ModelNode propDescr) {
            if(propDescr == null || !propDescr.isDefined()) {
                return Collections.emptyList();
            }
            if(!propDescr.has(Util.VALUE_TYPE)) {
                return Collections.emptyList();
            }

            // Empty value, returns the first char
            if (lastEnteredState == null) {
                if (propDescr.has(Util.TYPE)) {
                    ModelNode mt = propDescr.get(Util.TYPE);
                    if (typeEquals(mt, ModelType.OBJECT)) {
                        return Collections.singletonList("{");
                    } else if (typeEquals(mt, ModelType.LIST)) {
                        return Collections.singletonList("[");
                    }
                }
            }

            // Retrieves the type of the current instance.
            ModelNode propType = null;
            if (currentInstance != null) {
                propType = currentInstance.getType();
            }
            if (propType == null) {
                return Collections.emptyList();
            }
            // Retrieve the last property (if any)
            Instance.Property last = currentInstance.getLastProperty();

            // On list separator, or when no property exists,
            // complete with the next item in the list or the next property
            // inside an Object
            if (lastEnteredState.equals(ListItemSeparatorState.ID)
                    || last == null) {
                return completeNewProperty(propType);
            }

            // At this point, we have a property that is not terminated.

            // Are we completing after the equals?
            // If yes, complete with possible values.
            if (lastEnteredState.equals(EqualsState.ID)) {
                ModelNode pType = propType.get(last.name);
                if (pType.has(Util.TYPE)) {
                    final ModelNode mt = pType.get(Util.TYPE);
                    if (typeEquals(mt, ModelType.OBJECT)) {
                        return Collections.singletonList("{");
                    } else if (typeEquals(mt, ModelType.LIST)) {
                        return Collections.singletonList("[");
                    }
                }
                return getSimpleValues(propType, last.name, "");
            }

            // a piece of value?
            if (last.value != null) {
                if (last.name != null) {
                    return getSimpleValues(propType, last.name, last.value.asString());
                } else // An empty name
                // Could be the end of a list item, propose the next one or end.
                    if ((currentInstance instanceof ListInstance)
                            && !currentInstance.isComplete()) {
                        List<String> candidates = new ArrayList<>(getSimpleValues(currentInstance.type,
                                null, last.value.asString()));
                        // Add separator only for complex types, a simple type, such as a String,
                        // could lead to empty candidates too.
                        if (candidates.isEmpty() && isObject(propType)) {
                            candidates.add("]");
                            candidates.add(",");
                        }
                        Collections.sort(candidates);
                        return candidates;
                    } else {
                        return Collections.<String>emptyList();
                    }
            }

            // A piece of name?
            if (last.name != null) {
                final List<String> candidates = new ArrayList<>();
                for (String p : propType.keys()) {
                    if (p.startsWith(last.name) && !currentInstance.contains(p)) {
                        candidates.add(p);
                    }
                }
                // Inline the equals.
                if (candidates.size() == 1) {
                    if (last.name.equals(candidates.get(0))) {
                        candidates.set(0, last.name + "=");
                    }
                }
                Collections.sort(candidates);
                return candidates;
            }

            return Collections.emptyList();
        }

        private Collection<String> getSimpleValues(ModelNode propType, String name,
                String radical) {
            // name could be null of List properties
            if (name != null) {
                propType = propType.get(name);
            }
            final List<ModelNode> allowed;
            if (!propType.has(Util.ALLOWED)) {
                if (isBoolean(propType)) {
                    allowed = BOOLEAN_LIST;
                } else {
                    List<String> candidates = getCandidatesFromMetadata(propType,
                            radical);
                    if (candidates != null) {
                        return candidates;
                    }
                    return Collections.<String>emptyList();
                }
            } else {
                allowed = propType.get(Util.ALLOWED).asList();
            }
            List<String> candidates = new ArrayList<>();
            for (ModelNode candidate : allowed) {
                String c = candidate.asString();
                if (c.startsWith(radical)) {
                    candidates.add(candidate.asString());
                }
            }
            Collections.sort(candidates);
            return candidates;
        }

        // Completion for a new property
        private Collection<String> completeNewProperty(ModelNode propType) {
            if (currentInstance instanceof ListInstance) {
                // This is inside a list
                try {
                    // A list of booleans
                    ModelType mt = propType.asType();
                    if (mt.equals(ModelType.BOOLEAN)) {
                        List<String> candidates = new ArrayList<>();
                        for (ModelNode candidate : BOOLEAN_LIST) {
                            candidates.add(candidate.asString());
                        }
                        Collections.sort(candidates);
                        return candidates;
                    } else {
                        List<String> candidates = null;
                        if (!mt.equals(ModelType.OBJECT)) {
                            candidates = getCandidatesFromMetadata(currentInstance.type,
                                    "");
                        } else {
                            candidates = getCandidatesFromMetadata(propType,
                                    "");
                        }
                        if (candidates != null) {
                            return candidates;
                        }
                        // We don't know, returns an empty list.
                        return Collections.<String>emptyList();
                    }
                } catch (IllegalArgumentException ex) {
                    // This is an Object, returns the start character.
                    return Collections.singletonList("{");
                }
            } else {
                // This is inside an instance.
                // 2 cases, an Object with a proper value-type that describes its structure.
                // or a Map<String, 'propType'>;
                if (propType.getType() == ModelType.OBJECT) {
                    // This is inside an instance.
                    final List<String> candidates = new ArrayList<>(propType.keys());
                    // Remove the properties already present
                    for (Instance.Property p : currentInstance.properties) {
                        candidates.remove(p.name);
                    }
                    Collections.sort(candidates);
                    return candidates;
                } else {
                    return Collections.<String>emptyList();
                }
            }
        }

        protected boolean isBoolean(ModelNode propType) {
            if (propType.has(Util.TYPE)) {
                return typeEquals(propType.get(Util.TYPE), ModelType.BOOLEAN);
            }
            return false;
        }

        @Override
        public void enteredState(ParsingContext ctx) throws CommandFormatException {
            lastEnteredState = ctx.getState().getId();
            lastStateIndex = ctx.getLocation();
            lastStateChar = ctx.getCharacter();

            if(logging) {
                final StringBuilder buf = new StringBuilder();
                for (int i = 0; i < offset; ++i) {
                    buf.append(offsetStep);
                }
                buf.append("entered '" + lastStateChar + "' " + lastEnteredState);
                System.out.println(buf.toString());
                if(lastEnteredState.equals(PropertyListState.ID)) {
                    ++offset;
                }
            }
            switch (lastEnteredState) {
                case StartListState.ID:
                case StartObjectState.ID: {
                    if (currentInstance == null) {
                        currentInstance = Instance.newInstance(null, ""+lastStateChar);
                        currentInstance.type = propDescr;
                    } else {
                        currentInstance = currentInstance.newPropertyValue(""+lastStateChar);
                    }
                    break;
                }
                case PropertyState.ID: {
                    if (currentInstance == null) {
                        throw new CommandFormatException("Invalid syntax.");
                    }
                    currentInstance.newProperty();
                    break;
                }
                case EqualsState.ID: {
                    if (currentInstance == null) {
                        throw new CommandFormatException("Invalid syntax.");
                    }
                    currentInstance.setPropertyName(propBuf.toString());
                    propBuf.setLength(0);
                    break;
                }
            }
        }

        @Override
        public void leavingState(ParsingContext ctx) throws CommandFormatException {
            final String id = ctx.getState().getId();

            if (logging) {
                if (id.equals(PropertyListState.ID)) {
                    --offset;
                }
                final StringBuilder buf = new StringBuilder();
                for (int i = 0; i < offset; ++i) {
                    buf.append(offsetStep);
                }
                buf.append("leaving '" + ctx.getCharacter() + "' " + id);
                System.out.println(buf.toString());
            }

            switch (id) {
                case TextState.ID:
                case PropertyState.ID: {
                    // Store the last chunk of value here.
                    if (propBuf.length() > 0) {
                        currentInstance.endProperty(propBuf.toString());
                        propBuf.setLength(0);
                    }
                    break;
                }
                case StartListState.ID:
                case StartObjectState.ID: {
                    currentInstance = currentInstance.setComplete(ctx.getCharacter());
                    if (ctx.getCharacter() == '}' || ctx.getCharacter() == ']') {
                        if (!ctx.isEndOfContent()) { // Skip the '{', '['
                            ctx.advanceLocation(1);
                        }
                    }
                    break;
                }
            }
        }

        @Override
        public void character(ParsingContext ctx) throws CommandFormatException {
            final String id = ctx.getState().getId();

            if(logging) {
                final StringBuilder buf = new StringBuilder();
                for (int i = 0; i < offset; ++i) {
                    buf.append(offsetStep);
                }
                buf.append("char '" + ctx.getCharacter() + "' " + id);
                System.out.println(buf.toString());
            }

            if(id.equals(PropertyState.ID)) {
                final char ch = ctx.getCharacter();
                if(ch != '"' && !Character.isWhitespace(ch)) {
                    propBuf.append(ch);
                }
            } else if(id.equals(TextState.ID)) {
                propBuf.append(ctx.getCharacter());
            } else if (id.equals(EscapeCharacterState.ID)) {
                propBuf.append(ctx.getCharacter());
            }
        }
    }

    /*    private final class EchoCallbackHandler implements ParsingStateCallbackHandler {

        private int offset = 0;
        private String offsetStep = "    ";
        private final StringBuilder parsingBuf;

        private EchoCallbackHandler(StringBuilder parsingBuf) {
            this.parsingBuf = parsingBuf;
        }

        @Override
        public void enteredState(ParsingContext ctx) throws CommandFormatException {
            final String id = ctx.getState().getId();

                final StringBuilder buf = new StringBuilder();
            for(int i = 0; i < offset; ++i) {
                buf.append(offsetStep);
            }
            buf.append("entered '" + ctx.getCharacter() + "' " + id);
            System.out.println(buf.toString());

            if(id.equals(PropertyState.ID)) {
                for(int i = 0; i < offset; ++i) {
                    parsingBuf.append(offsetStep);
                }
            } else if(id.equals(EqualsState.ID)) {
                parsingBuf.append('=');
            } else if(id.equals(PropertyListState.ID)) {
                ++offset;
            } else if(id.equals(StartObjectState.ID)) {
                parsingBuf.append('{').append(Util.LINE_SEPARATOR);
            } else if(id.equals(StartListState.ID)) {
                parsingBuf.append('[').append(Util.LINE_SEPARATOR);
            }
        }

        @Override
        public void leavingState(ParsingContext ctx) throws CommandFormatException {
            final String id = ctx.getState().getId();

            if(id.equals(PropertyListState.ID)) {
                --offset;
            }

                final StringBuilder buf = new StringBuilder();
            for(int i = 0; i < offset; ++i) {
                buf.append(offsetStep);
            }
            buf.append("leaving '" + ctx.getCharacter() + "' " + id);
            System.out.println(buf.toString());

            if(id.equals(ListItemSeparatorState.ID)) {
                parsingBuf.append(",");
                parsingBuf.append(Util.LINE_SEPARATOR);
            } else if(id.equals(StartObjectState.ID)) {
                parsingBuf.append(Util.LINE_SEPARATOR);
                for(int i = 0; i < offset; ++i) {
                    parsingBuf.append(offsetStep);
                }
                parsingBuf.append('}');
            } else if(id.equals(StartListState.ID)) {
                parsingBuf.append(Util.LINE_SEPARATOR);
                for(int i = 0; i < offset; ++i) {
                    parsingBuf.append(offsetStep);
                }
                parsingBuf.append(']');
            }
        }

        @Override
        public void character(ParsingContext ctx) throws CommandFormatException {
            final String id = ctx.getState().getId();

                final StringBuilder buf = new StringBuilder();
            for(int i = 0; i < offset; ++i) {
                buf.append(offsetStep);
            }
            buf.append("char '" + ctx.getCharacter() + "' " + id);
            System.out.println(buf.toString());

            if(id.equals(PropertyState.ID) || id.equals(TextState.ID)) {
                parsingBuf.append(ctx.getCharacter());
            }
        }
    }
     */
    public interface ValueTypeCandidatesProvider {
        Collection<String> getCandidates(String chunk);
    }

    abstract static class ValueTypeCandidatesState extends DefaultParsingState implements ValueTypeCandidatesProvider {

        private final Collection<String> candidates = new ArrayList<String>();

        ValueTypeCandidatesState(String id) {
            super(id);
        }

        protected void addCandidate(String candidate) {
            candidates.add(candidate);
        }
        protected void addCandidates(Collection<String> candidates) {
            this.candidates.addAll(candidates);
        }

        @Override
        public Collection<String> getCandidates(String chunk) {
            if(candidates.isEmpty()) {
                return Collections.emptyList();
            }
            if(chunk == null || chunk.length() == 0) {
                return candidates;
            }
            final List<String> filtered = new ArrayList<String>(candidates.size());
            for(String candidate : candidates) {
                if(candidate.startsWith(chunk)) {
                    filtered.add(candidate);
                }
            }
            return filtered;
        }
    }

    public static class InitialValueState extends ValueTypeCandidatesState {
        public static final String ID = "INITVAL";

        public static final InitialValueState INSTANCE = new InitialValueState();

        public InitialValueState() {
            this(PropertyState.INSTANCE);
        }

        public InitialValueState(final PropertyState prop) {
            super(ID);
            enterState('{', StartObjectState.INSTANCE);
            enterState('[', StartListState.INSTANCE);
            setDefaultHandler(new CharacterHandler() {
                @Override
                public void handle(ParsingContext ctx) throws CommandFormatException {
                    //ctx.enterState(prop);
                    ctx.enterState(PropertyListState.INSTANCE);
                }});
            addCandidate("{");
            addCandidate("[");
            addCandidates(prop.getCandidates(null));
        }
    }

    public static class StartObjectState extends DefaultParsingState {
        public static final String ID = "OBJ";

        private static StartObjectState INSTANCE = new StartObjectState();

        public StartObjectState() {
            super(ID);
            setDefaultHandler(new CharacterHandler(){
                @Override
                public void handle(ParsingContext ctx) throws CommandFormatException {
                    ctx.enterState(PropertyListState.INSTANCE);
                }});
            setIgnoreWhitespaces(true);
            setReturnHandler(new CharacterHandler(){
                @Override
                public void handle(ParsingContext ctx) throws CommandFormatException {
                    ctx.leaveState();
                }});
                }
        }

    public static class StartListState extends DefaultParsingState {
        public static final String ID = "LST";

        private static StartListState INSTANCE = new StartListState();

        public StartListState() {
            super(ID);
            setDefaultHandler(new CharacterHandler(){
                @Override
                public void handle(ParsingContext ctx) throws CommandFormatException {
                    ctx.enterState(PropertyListState.INSTANCE);
                }});
            setIgnoreWhitespaces(true);
            setReturnHandler(new CharacterHandler(){
                @Override
                public void handle(ParsingContext ctx) throws CommandFormatException {
                    if(!ctx.isEndOfContent()) {
                        ctx.advanceLocation(1);
                    }
                    ctx.leaveState();
                }});
                }
        }

    public static class PropertyListState extends DefaultParsingState {
        public static final String ID = "PROPLIST";

        public static final PropertyListState INSTANCE = new PropertyListState();

        public PropertyListState() {
            super(ID);
            setEnterHandler(new CharacterHandler() {
                @Override
                public void handle(ParsingContext ctx) throws CommandFormatException {
                    ctx.enterState(PropertyState.INSTANCE);
                }});
            setDefaultHandler(new CharacterHandler(){
                @Override
                public void handle(ParsingContext ctx) throws CommandFormatException {
                    ctx.enterState(PropertyState.INSTANCE);
                }});
            setReturnHandler(new CharacterHandler(){
                @Override
                public void handle(ParsingContext ctx) throws CommandFormatException {
                    if(ctx.isEndOfContent()) {
                        ctx.leaveState();
                        return;
                    }
                    final char ch = ctx.getCharacter();
                    getHandler(ch).handle(ctx);
                }
            });
            enterState(',', ListItemSeparatorState.INSTANCE);
            leaveState(']');
            leaveState('}');
            setIgnoreWhitespaces(true);
        }
    }

    public static class ListItemSeparatorState extends DefaultParsingState implements ValueTypeCandidatesProvider {
        public static final String ID = "ITMSEP";

        public static final ListItemSeparatorState INSTANCE = new ListItemSeparatorState();

        public ListItemSeparatorState() {
            super(ID);
            setEnterHandler(new CharacterHandler(){
                @Override
                public void handle(ParsingContext ctx) throws CommandFormatException {
                    if(!ctx.isEndOfContent()) {
                        ctx.advanceLocation(1);
                    }
                    ctx.leaveState();
                }});
                }

        @Override
        public Collection<String> getCandidates(String chunk) {
            return Collections.emptyList();
        }
    }

    public static class PropertyState extends DefaultParsingState implements ValueTypeCandidatesProvider {
        public static final String ID = "PROP";

        public static final PropertyState INSTANCE = new PropertyState();

        private final Collection<String> candidates = new ArrayList<String>(2);

        public PropertyState() {
            super(ID);
            this.setEnterHandler(new CharacterHandler(){
                @Override
                public void handle(ParsingContext ctx) throws CommandFormatException {
                    final char ch = ctx.getCharacter();
                    final CharacterHandler handler = getHandler(ch);
                    handler.handle(ctx);
                }
            });
            enterState('{', StartObjectState.INSTANCE);
            enterState('[', PropertyListState.INSTANCE);
            setDefaultHandler(WordCharacterHandler.IGNORE_LB_ESCAPE_ON);
            enterState('=', EqualsState.INSTANCE);
            setReturnHandler(new CharacterHandler(){
                @Override
                public void handle(ParsingContext ctx) throws CommandFormatException {
                    ctx.leaveState();
                }});
            leaveState(',');
            leaveState(']');
            leaveState('}');
            candidates.add("=");
        }

        @Override
        public Collection<String> getCandidates(String chunk) {
            // TODO and on the '=' I should add value candidates
            return candidates;
        }
    }

    public static class EqualsState extends ValueTypeCandidatesState {

        public static final String ID = "EQ";

        public static final EqualsState INSTANCE = new EqualsState();

        public EqualsState() {
            super(ID);
            setIgnoreWhitespaces(true);
            setDefaultHandler(new CharacterHandler() {
                @Override
                public void handle(ParsingContext ctx) throws CommandFormatException {
                    ctx.enterState(TextState.INSTANCE);
                }});
            putHandler('>', GlobalCharacterHandlers.NOOP_CHARACTER_HANDLER);
            enterState('{', StartObjectState.INSTANCE);
            enterState('[', StartListState.INSTANCE);
            addCandidate("{");
            addCandidate("[");
            setReturnHandler(GlobalCharacterHandlers.LEAVE_STATE_HANDLER);
        }
    }

    public static class TextState extends ValueTypeCandidatesState {

        public static final String ID = "TEXT";

        public static final TextState INSTANCE = new TextState();

        public TextState() {
            super(ID);
            setHandleEntrance(true);
            setDefaultHandler(WordCharacterHandler.IGNORE_LB_ESCAPE_ON);
            leaveState(',');
            leaveState('=');
            leaveState('}');
            leaveState(']');
        }
    }
}
