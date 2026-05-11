module GlowBoxEngine {
	exports engine.record;
	exports engine.execution;
	exports ui;
	exports engine;
	exports engine.catalog;
	exports engine.storage;
	exports engine.optimizer;
	exports engine.parser;
	exports engine.index.hash;
	exports engine.buffer;
	exports engine.index;

	requires javafx.base;
	requires javafx.controls;
	requires javafx.graphics;
	requires net.sf.jsqlparser;
}