package com.meetingautomation.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Reusable contract for the hierarchy and health slice of a document provider. */
public abstract class DocumentStructureProviderContractTest {
    protected abstract Supplier<DocumentStructureProvider> structureProviderFactory();

    protected abstract Supplier<DocumentStructureProvider> failingStructureProviderFactory();

    @Test
    protected void validatesConnectionAndDiscoversRequiredDirectChildren() {
        DocumentStructureProvider provider = structureProviderFactory().get();
        assertEquals(new ProviderHealth(true, true, true), provider.validateConnection());
        assertEquals(new DocumentStructure("root-configured", "meetings-child", "participants-child"),
                provider.discoverStructure("root-configured"));
    }

    @Test
    protected void incompleteHierarchyUsesStructureNotFoundContract() {
        DocumentProviderException failure = assertThrows(DocumentProviderException.class,
                () -> failingStructureProviderFactory().get().discoverStructure("missing-root-child"));
        assertEquals("DOCUMENT_STRUCTURE_NOT_FOUND", failure.code());
        assertEquals(422, failure.statusCode());
        assertFalse(failure.retryable());
    }
}
