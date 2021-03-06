/*******************************************************************************
 * Copyright (c) 2007, 2014 Ericsson and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Ericsson	AB		- Initial Implementation
 *     Alvaro Sanchez-Leon (Ericsson AB) - [Memory] Support 16 bit addressable size (Bug 426730)
 *******************************************************************************/
package org.eclipse.cdt.tests.dsf.gdb.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ExecutionException;

import org.eclipse.cdt.core.IAddress;
import org.eclipse.cdt.debug.core.ICDTLaunchConfigurationConstants;
import org.eclipse.cdt.dsf.concurrent.DataRequestMonitor;
import org.eclipse.cdt.dsf.concurrent.RequestMonitor;
import org.eclipse.cdt.dsf.datamodel.IDMContext;
import org.eclipse.cdt.dsf.debug.service.IExpressions;
import org.eclipse.cdt.dsf.debug.service.IExpressions.IExpressionDMContext;
import org.eclipse.cdt.dsf.debug.service.IFormattedValues;
import org.eclipse.cdt.dsf.debug.service.IMemory;
import org.eclipse.cdt.dsf.debug.service.IMemory.IMemoryChangedEvent;
import org.eclipse.cdt.dsf.debug.service.IMemory.IMemoryDMContext;
import org.eclipse.cdt.dsf.debug.service.IRunControl.StepType;
import org.eclipse.cdt.dsf.debug.service.IStack.IFrameDMContext;
import org.eclipse.cdt.dsf.mi.service.MIRunControl;
import org.eclipse.cdt.dsf.mi.service.command.events.MIStoppedEvent;
import org.eclipse.cdt.dsf.service.DsfServiceEventHandler;
import org.eclipse.cdt.dsf.service.DsfServicesTracker;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.cdt.tests.dsf.gdb.framework.AsyncCompletionWaitor;
import org.eclipse.cdt.tests.dsf.gdb.framework.BackgroundRunner;
import org.eclipse.cdt.tests.dsf.gdb.framework.BaseTestCase;
import org.eclipse.cdt.tests.dsf.gdb.framework.SyncUtil;
import org.eclipse.cdt.tests.dsf.gdb.launching.TestsPlugin;
import org.eclipse.cdt.utils.Addr64;
import org.eclipse.debug.core.model.MemoryByte;
import org.hamcrest.CoreMatchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

/*
 * This is the Memory Service test suite.
 * 
 * It is meant to be a regression suite to be executed automatically against
 * the DSF nightly builds.
 * 
 * It is also meant to be augmented with a proper test case(s) every time a
 * feature is added or in the event (unlikely :-) that a bug is found in the
 * Memory Service.
 * 
 * Refer to the JUnit4 documentation for an explanation of the annotations.
 */

@RunWith(BackgroundRunner.class)
public class MIMemoryTest extends BaseTestCase {
	private static final String EXEC_NAME = "MemoryTestApp.exe";

	private final AsyncCompletionWaitor fWait = new AsyncCompletionWaitor();
	private DsfSession          fSession;
	private DsfServicesTracker  fServicesTracker;
	private IMemoryDMContext    fMemoryDmc;
	private MIRunControl        fRunControl;
	private IMemory             fMemoryService;
	private IExpressions        fExpressionService;

	// Keeps track of the MemoryChangedEvents
	private final int BLOCK_SIZE = 256;
	private IAddress fBaseAddress;
	private Integer fMemoryChangedEventCount = new Integer(0);
	private boolean[] fMemoryAddressesChanged = new boolean[BLOCK_SIZE];

	@Rule
	final public ExpectedException expectedException = ExpectedException.none();

	// ========================================================================
	// Housekeeping stuff
	// ========================================================================



	@Override
	public void doBeforeTest() throws Exception {
		super.doBeforeTest();
		
	    fSession = getGDBLaunch().getSession();
	    fMemoryDmc = (IMemoryDMContext)SyncUtil.getContainerContext();
	    assert(fMemoryDmc != null);

	    Runnable runnable = new Runnable() {
            @Override
			public void run() {
       	    // Get a reference to the memory service
        		fServicesTracker = new DsfServicesTracker(TestsPlugin.getBundleContext(), fSession.getId());
        		assert(fServicesTracker != null);

        		fRunControl = fServicesTracker.getService(MIRunControl.class);
        		assert(fRunControl != null);

        		fMemoryService = fServicesTracker.getService(IMemory.class);
        		assert(fMemoryService != null);

        		fExpressionService = fServicesTracker.getService(IExpressions.class);
        		assert(fExpressionService != null);

        		fSession.addServiceEventListener(MIMemoryTest.this, null);
        		fBaseAddress = null;
        		clearEventCounters();
            }
        };
        fSession.getExecutor().submit(runnable).get();
	}

	@Override
	protected void setLaunchAttributes() {
		super.setLaunchAttributes();
		
		// Select the binary to run the tests against
		setLaunchAttribute(ICDTLaunchConfigurationConstants.ATTR_PROGRAM_NAME, EXEC_PATH + EXEC_NAME);
	}

	@Override
	public void doAfterTest() throws Exception {
		super.doAfterTest();
		
		// Clear the references (not strictly necessary)
        Runnable runnable = new Runnable() {
            @Override
			public void run() {
            	fSession.removeServiceEventListener(MIMemoryTest.this);
            }
        };
        fSession.getExecutor().submit(runnable).get();
        
        fBaseAddress = null;
		fExpressionService = null;
		fMemoryService = null;
		fRunControl = null;
        fServicesTracker.dispose();
		fServicesTracker = null;
		clearEventCounters();
	}

	// ========================================================================
	// Helper Functions
	// ========================================================================

	 /* ------------------------------------------------------------------------
	 * eventDispatched
	 * ------------------------------------------------------------------------
	 * Processes MemoryChangedEvents.
	 * First checks if the memory block base address was set so the individual
	 * test can control if it wants to verify the event(s).   
	 * ------------------------------------------------------------------------
	 * @param e The MemoryChangedEvent
	 * ------------------------------------------------------------------------
	 */
	 @DsfServiceEventHandler
	 public void eventDispatched(IMemoryChangedEvent e) {
		 synchronized(fMemoryChangedEventCount) {
			 fMemoryChangedEventCount++;
		 }
		 IAddress[] addresses = e.getAddresses();
		 for (int i = 0; i < addresses.length; i++) {
			 int offset = Math.abs(addresses[i].distanceTo(fBaseAddress).intValue());
			 if (offset < BLOCK_SIZE)
				 synchronized(fMemoryAddressesChanged) {
					 fMemoryAddressesChanged[offset] = true;
				 }
		 }
	 }

	 // Clears the counters
	 private void clearEventCounters() {
		 synchronized(fMemoryChangedEventCount) {
			 fMemoryChangedEventCount = 0;
		 }
		 synchronized(fMemoryAddressesChanged) {
			 for (int i = 0; i < BLOCK_SIZE; i++)
				 fMemoryAddressesChanged[i] = false;
		 }
	 }

	 // Returns the total number of events received
	 private int getEventCount() {
		 int count;
		 synchronized(fMemoryChangedEventCount) {
			 count = fMemoryChangedEventCount;
		 }
		 return count;
	 }

	 // Returns the number of distinct addresses reported
	 private int getAddressCount() {
		 int count = 0;
		 synchronized(fMemoryAddressesChanged) {
			 for (int i = 0; i < BLOCK_SIZE; i++)
				 if (fMemoryAddressesChanged[i])
					 count++;
		 }
		 return count;
	 }

	 /* ------------------------------------------------------------------------
	 * evaluateExpression
	 * ------------------------------------------------------------------------
	 * Invokes the ExpressionService to evaluate an expression. In theory, we
	 * shouldn't rely on another service to test this one but we need a way to
	 * access a variable from the test application in order verify that the
	 * memory operations (read/write) are working properly.   
	 * ------------------------------------------------------------------------
	 * @param expression Expression to resolve
	 * @return Resolved expression  
	 * @throws InterruptedException
	 * ------------------------------------------------------------------------
	 */
	private IAddress evaluateExpression(IDMContext ctx, String expression) throws Throwable
	{
		IExpressionDMContext expressionDMC = SyncUtil.createExpression(ctx, expression);
		return new Addr64(SyncUtil.getExpressionValue(expressionDMC, IFormattedValues.HEX_FORMAT));
	}

	/* ------------------------------------------------------------------------
	 * readMemoryByteAtOffset
	 * ------------------------------------------------------------------------
	 * Issues a memory read request. The result is stored in fWait.
	 * ------------------------------------------------------------------------
	 * Typical usage:
	 *  getMemory(dmc, address, offset, count);
	 *  fWait.waitUntilDone(AsyncCompletionWaitor.WAIT_FOREVER);
	 *  assertTrue(fWait.getMessage(), fWait.isOK());
	 * ------------------------------------------------------------------------
	 * @param dmc		the data model context
	 * @param address	the memory block address
	 * @param offset	the offset in the buffer
	 * @param count		the number of bytes to read
	 * @param result	the expected byte
	 * @throws InterruptedException
	 * ------------------------------------------------------------------------
	 */
	private void readMemoryByteAtOffset(final IMemoryDMContext dmc, final IAddress address,
			final long offset, final int word_size, final int count, final MemoryByte[] result)
	throws InterruptedException
	{
		// Set the Data Request Monitor
		final DataRequestMonitor<MemoryByte[]> drm = 
			new DataRequestMonitor<MemoryByte[]>(fSession.getExecutor(), null) {
				@Override
				protected void handleCompleted() {
					if (isSuccess()) {
						result[(int) offset] = getData()[0];
					}
					fWait.waitFinished(getStatus());
				}
			};

		// Issue the get memory request
		fSession.getExecutor().submit(new Runnable() {
			@Override
			public void run() {
				fMemoryService.getMemory(dmc, address, offset, word_size, count, drm);
			}
		});
	}

	/* ------------------------------------------------------------------------
	 * writeMemory
	 * ------------------------------------------------------------------------
	 * Issues a memory write request.
	 * ------------------------------------------------------------------------
	 * Typical usage:
	 *  writeMemory(dmc, address, offset, count, buffer);
	 *  fWait.waitUntilDone(AsyncCompletionWaitor.WAIT_FOREVER);
	 *  assertTrue(fWait.getMessage(), fWait.isOK());
	 * ------------------------------------------------------------------------
	 * @param dmc		the data model context
	 * @param address	the memory block address (could be an expression)
	 * @param offset	the offset from address
	 * @param count		the number of bytes to write
	 * @param buffer	the byte buffer to write from
	 * @throws InterruptedException
	 * ------------------------------------------------------------------------
	 */
	private void writeMemory(final IMemoryDMContext dmc, final IAddress address,
			final long offset, final int word_size, final int count, final byte[] buffer)
	throws InterruptedException
	{
		// Set the Data Request Monitor
		final RequestMonitor rm = 
			new RequestMonitor(fSession.getExecutor(), null) {
				@Override
				protected void handleCompleted() {
					fWait.waitFinished(getStatus());
				}
			};

		// Issue the get memory request
		fSession.getExecutor().submit(new Runnable() {
			@Override
			public void run() {
				fMemoryService.setMemory(dmc, address, offset, word_size, count, buffer, rm);
			}
		});
	}

	// ========================================================================
	// Test Cases
	// ------------------------------------------------------------------------
	// Templates:
	// ------------------------------------------------------------------------
	// @ Test
	// public void basicTest() {
	//     // First test to run
	//     assertTrue("", true);
	// }
	// ------------------------------------------------------------------------
	// @ Test(timeout=5000)
	// public void timeoutTest() {
	//     // Second test to run, which will timeout if not finished on time
	//     assertTrue("", true);
	// }
	// ------------------------------------------------------------------------
	// @ Test(expected=FileNotFoundException.class)
	// public void exceptionTest() throws FileNotFoundException {
	//     // Third test to run which expects an exception
	//     throw new FileNotFoundException("Just testing");
	// }
	// ========================================================================

	///////////////////////////////////////////////////////////////////////////
	// getMemory tests
	///////////////////////////////////////////////////////////////////////////

	// ------------------------------------------------------------------------
	// readWithNullContext
	// Test that a null context is caught and generates an error
	// ------------------------------------------------------------------------
	@Test
	public void readWithNullContext() throws Throwable {

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
		IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		IMemoryDMContext dmc = null;
		long offset = 0;
		int word_size = 1;
		int count = 1;
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		expectedException.expect(ExecutionException.class);
		expectedException.expectMessage("Unknown context type");

		// Perform the test
		try {
			SyncUtil.readMemory(dmc, fBaseAddress, offset, word_size, count);
		} finally {
			// Ensure no MemoryChangedEvent event was received
			assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
		}
	}

	// ------------------------------------------------------------------------
	// readWithInvalidAddress
	// Test that an invalid address is caught and generates an error
	// ------------------------------------------------------------------------
	@Test
	public void readWithInvalidAddress() throws Throwable {

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		SyncUtil.step(StepType.STEP_RETURN);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = 1;
		fBaseAddress = new Addr64("0");

		// Perform the test
		MemoryByte[] buffer = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, offset, word_size, count);

		//	Ensure that we receive a block of invalid memory bytes
		assertEquals("Wrong value", (byte) 0, buffer[0].getValue());
		assertEquals("Wrong flags", (byte) 32, buffer[0].getFlags());

		// Ensure no MemoryChangedEvent event was received
		assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
	}

	// ------------------------------------------------------------------------
	// readWithInvalidWordSize
	// Test that an invalid word size is caught and generates an error
	// ------------------------------------------------------------------------
	@Test
	public void readWithInvalidWordSize() throws Throwable {

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
		IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int count = -1;
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		expectedException.expect(ExecutionException.class);
		expectedException.expectMessage("Word size not supported (< 1)");

		// Perform the test
		try {
			SyncUtil.readMemory(fMemoryDmc, fBaseAddress, offset, 0, count);
		} finally {
			// Ensure no MemoryChangedEvent event was received
			assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
		}
	}

	// ------------------------------------------------------------------------
	// readWithInvalidCount
	// Test that an invalid count is caught and generates an error
	// ------------------------------------------------------------------------
	@Test
	public void readWithInvalidCount() throws Throwable {

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = -1;
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		expectedException.expect(ExecutionException.class);
		expectedException.expectMessage("Invalid word count (< 0)");

		// Perform the test
		try {
			SyncUtil.readMemory(fMemoryDmc, fBaseAddress, offset, word_size, count);
		} finally {
			// Ensure no MemoryChangedEvent event was received
			assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
		}
	}

	// ------------------------------------------------------------------------
	// readCharVaryingBaseAddress
	// Test the reading of individual bytes by varying the base address
	// ------------------------------------------------------------------------
	@Test
	public void readCharVaryingBaseAddress() throws Throwable {

		// Run to the point where the variable is zeroed
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
		IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = 1;
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		// Verify that all bytes are '0'
		for (int i = 0; i < BLOCK_SIZE; i++) {
			IAddress address = fBaseAddress.add(i);
			MemoryByte[] buffer = SyncUtil.readMemory(fMemoryDmc, address, offset, word_size, count);
			assertEquals("Wrong value read at offset " + i, (byte) 0, buffer[0].getValue());
		}

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:setBlocks", true);
		SyncUtil.resumeUntilStopped();
		SyncUtil.step(StepType.STEP_RETURN);

		// Verify that all bytes are set
		for (int i = 0; i < BLOCK_SIZE; i++) {
			IAddress address = fBaseAddress.add(i);
			MemoryByte[] buffer = SyncUtil.readMemory(fMemoryDmc, address, offset, word_size, count);
			assertEquals("Wrong value read at offset " + i, (byte) i, buffer[0].getValue());
		}

		// Ensure no MemoryChangedEvent event was received
		assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
	}

	// ------------------------------------------------------------------------
	// readCharVaryingOffset
	// Test the reading of individual bytes by varying the offset
	// ------------------------------------------------------------------------
	@Test
	public void readCharVaryingOffset() throws Throwable {

		// Run to the point where the array is zeroed
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		int word_size = 1;
		int count = 1;
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		// Verify that all bytes are '0'
		for (int offset = 0; offset < BLOCK_SIZE; offset++) {
			MemoryByte[] buffer = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, offset, word_size, count);
			assertEquals("Wrong value read at offset " + offset, (byte) 0, buffer[0].getValue());

		}

		// Run to the point where the array is set
		SyncUtil.addBreakpoint("MemoryTestApp.cc:setBlocks", true);
		SyncUtil.resumeUntilStopped();
		SyncUtil.step(StepType.STEP_RETURN);

		// Verify that all bytes are set
		for (int offset = 0; offset < BLOCK_SIZE; offset++) {
			MemoryByte[] buffer = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, offset, word_size, count);
			assertEquals("Wrong value read at offset " + offset, (byte) offset, buffer[0].getValue());
		}

		// Ensure no MemoryChangedEvent event was received
		assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
	}

	// ------------------------------------------------------------------------
	// readCharArray
	// Test the reading of a byte array
	// ------------------------------------------------------------------------
	@Test
	public void readCharArray() throws Throwable {

		// Run to the point where the variable is zeroed
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = BLOCK_SIZE;
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		// Get the memory block
		MemoryByte[] buffer = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, offset, word_size, count);

		// Verify that all bytes are '0'
		for (int i = 0; i < count; i++) {
			assertEquals("Wrong value read at offset " + i, (byte) 0, buffer[i].getValue());

		}

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:setBlocks", true);
		SyncUtil.resumeUntilStopped();
		SyncUtil.step(StepType.STEP_RETURN);

		// Get the memory block
		buffer = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, offset, word_size, count);

		// Verify that all bytes are '0'
		for (int i = 0; i < count; i++) {
			assertEquals("Wrong value read at offset " + i, (byte) i, buffer[i].getValue());

		}

		// Ensure no MemoryChangedEvent event was received
		assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
	}

	///////////////////////////////////////////////////////////////////////////
	// setMemory tests
	///////////////////////////////////////////////////////////////////////////

	// ------------------------------------------------------------------------
	// writeWithNullContext
	// Test that a null context is caught and generates an error
	// ------------------------------------------------------------------------
	@Test
	public void writeWithNullContext() throws Throwable {

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = 1;
		byte[] buffer = new byte[count];
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		expectedException.expect(ExecutionException.class);
		expectedException.expectMessage("Unknown context type");

		// Perform the test
		try {
			SyncUtil.writeMemory(null, fBaseAddress, offset, word_size, count, buffer);
		} finally {
			// Ensure no MemoryChangedEvent event was received
			assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
		}
	}

	// ------------------------------------------------------------------------
	// writeWithInvalidAddress
	// Test that an invalid address is caught and generates an error
	// ------------------------------------------------------------------------
	@Test
	public void writeWithInvalidAddress() throws Throwable {

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		SyncUtil.step(StepType.STEP_RETURN);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = 1;
		byte[] buffer = new byte[count];
		fBaseAddress = new Addr64("0");

		expectedException.expect(ExecutionException.class);
		// Don't test the error message since it changes from one GDB version to another
		// TODO: there is another test that does it, we could do it.

		// Perform the test
		try {
			SyncUtil.writeMemory(fMemoryDmc, fBaseAddress, offset, word_size, count, buffer);
		} finally {
			// Ensure no MemoryChangedEvent event was received
			assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
		}
	}

	// ------------------------------------------------------------------------
	// writeWithInvalidWordSize
	// Test that an invalid word size is caught and generates an error
	// ------------------------------------------------------------------------
	@Test
	public void writeWithInvalidWordSize() throws Throwable {

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int count = -1;
		byte[] buffer = new byte[1];
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		expectedException.expect(ExecutionException.class);
		expectedException.expectMessage("Word size not supported (< 1)");

		// Perform the test
		try {
			SyncUtil.writeMemory(fMemoryDmc, fBaseAddress, offset, 0, count, buffer);
		} finally {
			// Ensure no MemoryChangedEvent event was received
			assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
		}
	}

	// ------------------------------------------------------------------------
	// writeWithInvalidCount
	// Test that an invalid count is caught and generates an error
	// ------------------------------------------------------------------------
	@Test
	public void writeWithInvalidCount() throws Throwable {

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = -1;
		byte[] buffer = new byte[1];
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		expectedException.expect(ExecutionException.class);
		expectedException.expectMessage("Invalid word count (< 0)");

		// Perform the test
		try {
			SyncUtil.writeMemory(fMemoryDmc, fBaseAddress, offset, word_size, count, buffer);
		} finally {
			// Ensure no MemoryChangedEvent event was received
			assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
		}
	}

	// ------------------------------------------------------------------------
	// writeWithInvalidBuffer
	// Test that the buffer contains at least count bytes
	// ------------------------------------------------------------------------
	@Test
	public void writeWithInvalidBuffer() throws Throwable {

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
		IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = 10;
		byte[] buffer = new byte[count - 1];
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		expectedException.expect(ExecutionException.class);
		expectedException.expectMessage("Buffer too short");

		// Perform the test
		try {
			SyncUtil.writeMemory(fMemoryDmc, fBaseAddress, offset, word_size, count, buffer);
		} finally {
			// Ensure no MemoryChangedEvent event was received
			assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
		}
	}

	// ------------------------------------------------------------------------
	// writeCharVaryingAddress
	// Test the writing of individual bytes by varying the base address
	// ------------------------------------------------------------------------
	@Test
	public void writeCharVaryingAddress() throws Throwable {

		// Run to the point where the variable is zeroed
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = BLOCK_SIZE;
		byte[] buffer = new byte[count];
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		// Perform the test
		for (int i = 0; i < count; i++) {
			
			// [1] Ensure that the memory byte = 0
			MemoryByte[] block = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, i, word_size, 1);
			assertEquals("Wrong value read at offset " + i, (byte) 0, block[0].getValue());

			
			// [2] Write a byte value (count - i - 1)
			IAddress address = fBaseAddress.add(i);
			fWait.waitReset();
			byte expected = (byte) (count - i - 1);
			buffer[0] = expected;
			SyncUtil.writeMemory(fMemoryDmc, address, offset, word_size, 1, buffer);

			// [3] Verify that the correct MemoryChangedEvent was sent
			// (I hardly believe there are no synchronization problems here...)
			// TODO FOR REVIEW: This assert fails
			//assertEquals("Incorrect count of MemoryChangedEvent at offset " + i, i + 1, getEventCount());
			//assertTrue("MemoryChangedEvent problem at offset " + i, fMemoryAddressesChanged[i]);

			// [4] Verify that the memory byte was written correctly
			block = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, i, word_size, 1);
			assertEquals("Wrong value read at offset " + i, expected, block[0].getValue());
		}

		// Ensure the MemoryChangedEvent events were received
		assertEquals("Incorrect count of MemoryChangedEvent", BLOCK_SIZE, getEventCount());
		assertEquals("Incorrect count of events for distinct addresses", BLOCK_SIZE, getAddressCount());
	}

	// ------------------------------------------------------------------------
	// writeCharVaryingOffset
	// Test the writing of individual bytes by varying the base address
	// ------------------------------------------------------------------------
	@Test
	public void writeCharVaryingOffset() throws Throwable {

		// Run to the point where the variable is zeroed
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		int word_size = 1;
		int count = BLOCK_SIZE;
		byte[] buffer = new byte[count];
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		// Perform the test
		for (int offset = 0; offset < count; offset++) {
			
			// [1] Ensure that the memory byte = 0
			MemoryByte[] block = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, offset, word_size, 1);
			assertEquals("Wrong value read at offset " + offset, (byte) 0, block[0].getValue());
			
			// [2] Write a byte value (count - offset - 1)
			fWait.waitReset();
			byte expected = (byte) (count - offset - 1);
			buffer[0] = expected;
			SyncUtil.writeMemory(fMemoryDmc, fBaseAddress, offset, word_size, 1, buffer);

			// [3] Verify that the correct MemoryChangedEvent was sent
			// TODO FOR REVIEW: this fails
			//assertEquals("Incorrect count of MemoryChangedEvent at offset " + offset, offset + 1, getEventCount());
			//assertTrue("MemoryChangedEvent problem at offset " + offset, fMemoryAddressesChanged[offset]);

			// [4] Verify that the memory byte was written correctly
			block = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, offset, word_size, 1);
			assertEquals("Wrong value read at offset " + offset, expected, block[0].getValue());
		}

		// Ensure the MemoryChangedEvent events were received
		assertEquals("Incorrect count of MemoryChangedEvent", BLOCK_SIZE, getEventCount());
		assertEquals("Incorrect count of events for distinct addresses", BLOCK_SIZE, getAddressCount());
	}

	// ------------------------------------------------------------------------
	// writeCharArray
	// Test the writing of a byte array
	// ------------------------------------------------------------------------
	@Test
	public void writeCharArray() throws Throwable {

		// Run to the point where the variable is zeroed
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = BLOCK_SIZE;
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		// Make sure that the memory block is zeroed
		MemoryByte[] block = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, offset, word_size, count);
		for (int i = 0; i < count; i++) {
			assertEquals("Wrong value read at offset " + i, (byte) 0, block[i].getValue());
		}

		// Write an initialized memory block
		byte[] buffer = new byte[count];
		for (int i = 0; i < count; i++) {
			buffer[i] = (byte) i;
		}
		SyncUtil.writeMemory(fMemoryDmc, fBaseAddress, offset, word_size, count, buffer);

		// Make sure that the memory block is initialized
		block = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, offset, word_size, count);
		for (int i = 0; i < count; i++) {
			assertEquals("Wrong value read at offset " + i, (byte) i, block[i].getValue());
		}

		// Ensure the MemoryChangedEvent events were received
		assertEquals("Incorrect count of MemoryChangedEvent", 1, getEventCount());
		assertEquals("Incorrect count of events for distinct addresses", BLOCK_SIZE, getAddressCount());
	}

	///////////////////////////////////////////////////////////////////////////
	// fillMemory tests
	///////////////////////////////////////////////////////////////////////////

	// ------------------------------------------------------------------------
	// fillWithNullContext
	// Test that a null context is caught and generates an error
	// ------------------------------------------------------------------------
	@Test
	public void fillWithNullContext() throws Throwable {

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = 1;
		byte[] pattern = new byte[count];
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		expectedException.expect(ExecutionException.class);
		expectedException.expectMessage("Unknown context type");

		// Perform the test
		try {
			SyncUtil.fillMemory(null, fBaseAddress, offset, word_size, count, pattern);
		} finally {
			// Ensure no MemoryChangedEvent event was received
			assertEquals("Incorrect count of MemoryChangedEvent", 0, getEventCount());
		}
	}

	// ------------------------------------------------------------------------
	// fillWithInvalidAddress
	// Test that an invalid address is caught and generates an error
	// ------------------------------------------------------------------------
	@Test
	public void fillWithInvalidAddress() throws Throwable {

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		SyncUtil.step(StepType.STEP_RETURN);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = 1;
		byte[] pattern = new byte[count];
		fBaseAddress = new Addr64("0");

		// Depending on the GDB, a different command can be used.  Both error message are valid.
		// Error message for -data-write-memory command
		String expectedStr1 = "Cannot access memory at address";
		// Error message for new -data-write-memory-bytes command
		String expectedStr2 = "Could not write memory";
		expectedException.expect(ExecutionException.class);
		expectedException.expectMessage(CoreMatchers.anyOf(
				CoreMatchers.containsString(expectedStr1),
				CoreMatchers.containsString(expectedStr2)));

		// Perform the test
		try {
			SyncUtil.fillMemory(fMemoryDmc, fBaseAddress, offset, word_size, count, pattern);
		} finally {
			// Ensure no MemoryChangedEvent event was received
			assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
		}
	}

	// ------------------------------------------------------------------------
	// fillWithInvalidWordSize
	// Test that an invalid word size is caught and generates an error
	// ------------------------------------------------------------------------
	@Test
	public void fillWithInvalidWordSize() throws Throwable {

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int count = 1;
		byte[] pattern = new byte[1];
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		expectedException.expect(ExecutionException.class);
		expectedException.expectMessage("Word size not supported (< 1)");

		// Perform the test
		try {
			SyncUtil.fillMemory(fMemoryDmc, fBaseAddress, offset, 0, count, pattern);
		} finally {
			// Ensure no MemoryChangedEvent event was received
			assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
		}
	}

	// ------------------------------------------------------------------------
	// fillWithInvalidCount
	// Test that an invalid count is caught and generates an error
	// ------------------------------------------------------------------------
	@Test
	public void fillWithInvalidCount() throws Throwable {

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = -1;
		byte[] pattern = new byte[1];
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		expectedException.expect(ExecutionException.class);
		expectedException.expectMessage("Invalid repeat count (< 0)");

		// Perform the test
		try {
			SyncUtil.fillMemory(fMemoryDmc, fBaseAddress, offset, word_size, count, pattern);
		} finally {
			// Ensure no MemoryChangedEvent event was received
			assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
		}
	}

	// ------------------------------------------------------------------------
	// fillWithInvalidPattern
	// Test that an empty pattern is caught and generates an error
	// ------------------------------------------------------------------------
	@Test
	public void fillWithInvalidPattern() throws Throwable {

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = 1;
		byte[] pattern = new byte[0];
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		expectedException.expect(ExecutionException.class);
		expectedException.expectMessage("Empty pattern");

		// Perform the test
		try {
			SyncUtil.fillMemory(fMemoryDmc, fBaseAddress, offset, word_size, count, pattern);
		} finally {
			// Ensure no MemoryChangedEvent event was received
			assertEquals("MemoryChangedEvent problem: expected 0 events", 0, getEventCount());
		}
	}

	// ------------------------------------------------------------------------
	// writePatternVaryingAddress
	// Test the writing of the pattern by varying the base address
	// ------------------------------------------------------------------------
	@Test
	public void writePatternVaryingAddress() throws Throwable {

		// Run to the point where the variable is zeroed
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = 1;
		int length = 4;
		byte[] pattern = new byte[length];
		for (int i = 0; i < length; i++) pattern[i] = (byte) i;
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		// Ensure that the memory is zeroed
		MemoryByte[] block = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, offset, word_size, count * length);
		for (int i = 0; i < (count * length); i++)
			assertEquals("Wrong value read at offset " + i, (byte) 0, block[i].getValue());

		for (int i = 0; i < BLOCK_SIZE; i += length) {
			IAddress address = fBaseAddress.add(i);
			SyncUtil.fillMemory(fMemoryDmc, address, offset, word_size, count, pattern);
		}

		// Verify that the memory is correctly set
		block = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, 0, word_size, count * length);
		for (int i = 0; i < count; i++)
			for (int j = 0; j < length; j++) {
				int index = i * length + j;
				assertEquals("Wrong value read at offset " + index, (byte) j, block[index].getValue());
			}

		// Ensure the MemoryChangedEvent events were received
		assertEquals("Incorrect count of MemoryChangedEvent", BLOCK_SIZE / length, getEventCount());
		assertEquals("Incorrect count of events for distinct addresses", BLOCK_SIZE, getAddressCount());
	}

	// ------------------------------------------------------------------------
	// writePatternVaryingOffset
	// Test the writing of the pattern by varying the base address
	// ------------------------------------------------------------------------
	@Test
	public void writePatternVaryingOffset() throws Throwable {

		// Run to the point where the variable is zeroed
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = 64;
		int length = 4;
		byte[] pattern = new byte[length];
		for (int i = 0; i < length; i++) pattern[i] = (byte) i;
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		// Ensure that the memory is zeroed
		MemoryByte[] block = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, offset, word_size, count * length);
		for (int i = 0; i < (count * length); i++)
			assertEquals("Wrong value read at offset " + i, 0, block[i].getValue());

		for (int i = 0; i < (BLOCK_SIZE / length); i++) {
			offset = i * length;
			SyncUtil.fillMemory(fMemoryDmc, fBaseAddress, offset, word_size, 1, pattern);
		}

		// Verify that the memory is correctly set
		block = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, 0, word_size, count * length);
		for (int i = 0; i < count; i++)
			for (int j = 0; j < length; j++) {
				int index = i * length + j;
				assertEquals("Wrong value read at offset " + index, (byte) j, block[index].getValue());
			}

		// Ensure the MemoryChangedEvent events were received
		assertEquals("Incorrect count of MemoryChangedEvent", BLOCK_SIZE / length, getEventCount());
		assertEquals("Incorrect count of events for distinct addresses", BLOCK_SIZE, getAddressCount());

	}

	// ------------------------------------------------------------------------
	// writePatternCountTimes
	// Test the writing of the pattern [count] times
	// ------------------------------------------------------------------------
	@Test
	public void writePatternCountTimes() throws Throwable {

		// Run to the point where the variable is zeroed
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		long offset = 0;
		int word_size = 1;
		int count = 64;
		int length = 4;
		byte[] pattern = new byte[length];
		for (int i = 0; i < length; i++) pattern[i] = (byte) i;
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		// Ensure that the memory is zeroed
		MemoryByte[] block = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, offset, word_size, count * length);
		for (int i = 0; i < (count * length); i++)
			assertEquals("Wrong value read at offset " + i, (byte) 0, block[i].getValue());
		
		// Write the pattern [count] times
		SyncUtil.fillMemory(fMemoryDmc, fBaseAddress, offset, word_size, count, pattern);

		// Verify that the memory is correctly set
		block = SyncUtil.readMemory(fMemoryDmc, fBaseAddress, offset, word_size, count * length);
		for (int i = 0; i < count; i++)
			for (int j = 0; j < length; j++) {
				int index = i * length + j;
				assertEquals("Wrong value read at offset " + index, (byte) j, block[index].getValue());
			}

		// Ensure the MemoryChangedEvent events were received
		assertEquals("Incorrect count of MemoryChangedEvent", 1, getEventCount());
		assertEquals("Incorrect count of events for distinct addresses", BLOCK_SIZE, getAddressCount());

	}

	// ------------------------------------------------------------------------
	// asynchronousReadWrite
	// Test the asynchronous reading/writing of individual bytes (varying offset)
	// ------------------------------------------------------------------------
	@Test
	public void asynchronousReadWrite() throws Throwable {

		// Run to the point where the array is zeroed
		SyncUtil.addBreakpoint("MemoryTestApp.cc:zeroBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		int word_size = 1;
		int count = 1;
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		// Interesting issue. Believe it or not, requests can get serviced 
		// faster than we can queue them. E.g., we queue up five, and before we
		// queue the sixth, the five are serviced. Before, when that happened
		// the waitor went into the 'complete' state before we were done queuing
		// all the requests. To avoid that, we need to add our own tick and then
		// clear it once we're done queuing all the requests.
		
		// Verify asynchronously that all bytes are '0'
		fWait.waitReset();
		fWait.increment();	// see "Interesting issue" comment above  
		MemoryByte[] buffer = new MemoryByte[BLOCK_SIZE];
		for (int offset = 0; offset < BLOCK_SIZE; offset++) {
			fWait.increment();
			readMemoryByteAtOffset(fMemoryDmc, fBaseAddress, offset, word_size, count, buffer);
		}
		fWait.waitFinished();	// see "Interesting issue" comment above
		fWait.waitUntilDone(AsyncCompletionWaitor.WAIT_FOREVER);
		assertTrue(fWait.getMessage(), fWait.isOK());
		for (int offset = 0; offset < BLOCK_SIZE; offset++) {
			assertTrue("Wrong value read at offset " + offset + ": expected '" + 0 + "', received '" + buffer[offset].getValue() + "'",
					(buffer[offset].getValue() == (byte) 0));
		}

		// Write asynchronously
		fWait.waitReset();
		fWait.increment(); 	// see "Interesting issue" comment above		
		for (int offset = 0; offset < BLOCK_SIZE; offset++) {
			fWait.increment();
			byte[] block = new byte[count];
			block[0] = (byte) offset;
			writeMemory(fMemoryDmc, fBaseAddress, offset, word_size, count, block);
		}
		fWait.waitFinished();	// see "Interesting issue" comment above
		fWait.waitUntilDone(AsyncCompletionWaitor.WAIT_FOREVER);
		assertTrue(fWait.getMessage(), fWait.isOK());

		// Ensure the MemoryChangedEvent events were received
		// TODO FOR REVIEW: This fails
		//assertEquals("Incorrect count of MemoryChangedEvent", BLOCK_SIZE, getEventCount());
		//assertEquals("Incorrect count of events for distinct addresses", BLOCK_SIZE, getAddressCount());

		// Verify asynchronously that all bytes are set
		fWait.waitReset();
		fWait.increment();	// see "Interesting issue" comment above
		for (int offset = 0; offset < BLOCK_SIZE; offset++) {
			fWait.increment();
			readMemoryByteAtOffset(fMemoryDmc, fBaseAddress, offset, word_size, count, buffer);
		}
		fWait.waitFinished();	// see "Interesting issue" comment above
		fWait.waitUntilDone(AsyncCompletionWaitor.WAIT_FOREVER);
		assertTrue(fWait.getMessage(), fWait.isOK());
		for (int offset = 0; offset < BLOCK_SIZE; offset++) {
			assertTrue("Wrong value read at offset " + offset + ": expected '" + offset + "', received '" + buffer[offset].getValue() + "'",
					(buffer[offset].getValue() == (byte) offset));
		}
	}

	private void memoryCacheReadHelper(int offset, int count, int word_size)
			throws InterruptedException, ExecutionException {
		MemoryByte[] buffer = SyncUtil.readMemory(fMemoryDmc, fBaseAddress,
				offset, word_size, count);

		// Verify that all bytes are correctly set
		for (int i = 0; i < count; i++) {
			assertEquals("Wrong value read at offset " + i, (byte) (offset + i), buffer[i].getValue());
		}
	}

	// ------------------------------------------------------------------------
	// memoryCacheRead
	// Get a bunch of blocks to exercise the memory cache 
	// ------------------------------------------------------------------------
	@Test
	public void memoryCacheRead() throws Throwable {

		// Run to the point where the variable is initialized
		SyncUtil.addBreakpoint("MemoryTestApp.cc:setBlocks", true);
		SyncUtil.resumeUntilStopped();
		MIStoppedEvent stoppedEvent = SyncUtil.step(StepType.STEP_RETURN);
        IFrameDMContext frameDmc = SyncUtil.getStackFrame(stoppedEvent.getDMContext(), 0);

		// Setup call parameters
		int word_size = 1;
		fBaseAddress = evaluateExpression(frameDmc, "&charBlock");

		// Get the 'reference' memory block
		memoryCacheReadHelper(0, BLOCK_SIZE, word_size);

		// Clear the cache
		SyncUtil.step(StepType.STEP_OVER);

		// Get a first block
		memoryCacheReadHelper(0, 64, word_size);

		// Get a second block
		memoryCacheReadHelper(128, 64, word_size);

		// Get a third block between the first 2
		memoryCacheReadHelper(80, 32, word_size);

		// Get a block that is contiguous to the end of an existing block
		memoryCacheReadHelper(192, 32, word_size);

		// Get a block that ends beyond an existing block
		memoryCacheReadHelper(192, 64, word_size);

		// Get a block that will require 2 reads (for the gaps between blocks 1-2 and 2-3)
		memoryCacheReadHelper(32, 128, word_size);

		// Get a block that involves multiple cached blocks
		memoryCacheReadHelper(48, 192, word_size);

		// Get the whole block
		memoryCacheReadHelper(0, BLOCK_SIZE, word_size);

		// Ensure no MemoryChangedEvent event was received
		assertEquals("Incorrect count of MemoryChangedEvent", 0, getEventCount());
	}
}
