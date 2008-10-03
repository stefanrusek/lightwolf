====================================
How To Setup Development Environment
====================================

1. Use Eclipse 3.4 or newer.
2. Import a new project, using this directory contents.
   Eclipse should create a project named "lightwolf".
3. Run the launch configuration "launch/lightwolf build.xml.launch".
   This will fill the enhbin directory with the required .class files.
4. Run the launch configuration "launch/AllTests on enhbin.launch".
   You should see a bunch of successful tests. You can debug the sources now.

After investigating the above launches and sources, you should be able to discover how
it was developed and what's going on.

============================================================================
Enabling step-filters (avoiding implicit calls to org.lightwolf.MethodFrame)
============================================================================

While debugging, you will notice some strange calls to class org.lightwolf.MethodFrame.
These calls are normal and are consequence of bytecode transformation. But they quite
annoying for people that doesn't need to know how the stack values are being tracked.
Fortunately you can avoid stepping through these calls by enabling Eclipse's step filters.

While in Debug view, on a running program, right-click anywhere in a stack-trace, and
choose "Edit Step Filters...". Add the class org.lightwolf.MethodFrame, and click OK.
Right-click again, and make sure that the option "Use Step Filters" is checked.

==================
That's all for now
==================

When I have more time, I will put more documentation. Please, notice that this is still
Pre-Alpha. Thanks for your patience.

Fernando Colombo
 