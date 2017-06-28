import  os, sys
#import xml.etree.ElementTree as ET
#from lxml import etree as ET
import xml.dom.minidom
from xml.dom.minidom import parse,parseString,Node

from proputils import INFO, WARNING, ERROR, FATAL,DEBUG, SET_DEBUG
import properties

deploymentmessage = ""
deployprops = []
deploypropsdict = {}
DEPLOYMENT_SET = {}
PROPDEFROOT = "definitions/"

def setmessage(txt):
    deploymentmessage = txt
    
def getmessage():
    if len(deploymentmessage) > 0:
        return deploymentmessage
    else:
        return "Unknown internal error"
  
def load_deployment_defs():
    global DEPLOYMENT_SET, PROPDEFROOT
    DEPLOYMENT_SET = properties.load(PROPDEFROOT + "xml_property_set.conf")



def loadxmlfile(filename):
    global deployprops, deploypropsdict, DEPLOYMENT_MAP

    DEBUG("READ DEPLOYMENT FILE")
    tree = None
    try:
        tree = parse(filename)
    except Exception as e:
        FATAL("Cannot parse deployment file " + filename + ". " + str(e))
        
    return tree

def loadxmlstring(text):
    global deployprops, deploypropsdict, DEPLOYMENT_MAP

    DEBUG("READ DEPLOYMENT STRING")
    tree = None
    try:
        tree = parseString(text)
    except Exception as e:
        FATAL("Cannot parse configuration file text. " + str(e))
        
    return tree

def getxmldom(root):

        return root.toxml()

def walktree(xml,root):
    global deployprops, deploypropsdict
    
    #if xml.nodeName[:1] != "#":
    INFO("Node TYPE " + str(xml.nodeType))
    
    #DEBUG("NODE: " + xml.nodeName)

    if xml.nodeType ==  xml.TEXT_NODE and xml.nodeName[:1] != "#":
        INFO("NODE " + root + ": " + xml.data)

    if xml.attributes:
        for att, value in xml.attributes.items():
            INFO("ATTRIBUTE " + root + "." + att + ": " + value)
      

    if xml.childNodes:
        for child in xml.childNodes:
            newroot = root
            if newroot != "":
                newroot = newroot + "."
            newroot = newroot + child.nodeName
            INFO("NODE " + newroot)
            walktree (child, newroot) 
       
def getxml(r,p,uniqueIDs):
    props = p.split(".")

    root = r
    for n in props:
        DEBUG("Looking for " + n  + "...")
        if n[0:1] == ":":   #Attribute
            a = n[1:]
            att = root.getAttribute(a)
            if (att): return att
            #atts = root.attributes.keys()
            #if a in root.attributes:
            #    if a.return atts[a].value
            #else:
            DEBUG("No such attribute as " + n)
            return None
        else:               #Node
            children = root.childNodes
            newroot = None
            for c in children:
                if c.nodeType is not Node.ELEMENT_NODE: 
                    #DEBUG("node is not an element...")
                    continue
                if (c.tagName == n): newroot = c
            if newroot is None: return None
            root = newroot
    # If we get here, we should have a node. Look for its text node child
    children = root.childNodes
    for c in children:
        if c.nodeType == Node.TEXT_NODE: return c.data
    return "[no value]"
                
def setxml(r,p,v,uniqueIDs):

    # Need to rewrite this as a separate routine to catch quoted attribute values
    props = p.split(".")
    
    uniquepointer = 0
    root = r
    level = 0
    if (root.nodeType == Node.DOCUMENT_NODE):
        root = root.getElementsByTagName("deployment")[0]
    for n in props:
        # Check if first element is valid
        if level == 0 and root.nodeType == Node.ELEMENT_NODE:
            if root.tagName == n:
                DEBUG("Root node is first element.")
                level = level + 1
                continue
        level = level + 1
        
            
        DEBUG("Looking for " + n  + "...")
        if n[0:1] == ":":   #Attribute
            a = n[1:]
            # This is an endpoint
            att = root.attributes[a] = v
            return 
        else:               #Node
            if root.nodeType is not Node.ELEMENT_NODE:
                WARNING("Root is not an element? " + str(root.nodeType))
                return
            # Check for required attribute
            attloc = n.find(":")
            attribute =[None,None]
            if attloc >= 0:
                a = n[attloc+1:]
                n = n[0:attloc]
                attloc = a.find("(")
                if attloc > -1:
                    attribute[0] = a[0:attloc]
                    a = a[attloc+1:]
                    attloc = a.find(")")
                    #We now assume the attribute value is the end of the attribute
                    if attloc < 0: FATAL("unmatched parenthesis")
                    if attloc < len(a) -1: WARNING("Ignoring text after attribute value")
                    attribute[1] = a[0:attloc]
                    # Finally, do replacement for wildcard attributes
                    if attribute[1] == "*":
                        if uniquepointer > len(uniqueIDs) -1:
                            FATAL("Internal error. Not enough unique IDs for XML definition.")
                        attribute[1] = uniqueIDs[uniquepointer]
                        uniquepointer = uniquepointer + 1
                    
            DEBUG("Looking for element " + n  + "...")
            if attribute[0]: DEBUG("Attribute " + attribute[0] + "=" + attribute[1])
            children = root.childNodes
            newroot = None
            for c in children:
                if c.nodeType is not Node.ELEMENT_NODE: 
                    #DEBUG("node is not an element...")
                    continue
                if (c.tagName == n): 
                    # if the tag name matches, see if the attribute matches
                    if attribute[0]:
                        DEBUG("Current attribute value for " + attribute[0] + " is [" + c.attributes[attribute[0]].value + "]")
                        if c.attributes[attribute[0]].value == attribute[1]:
                            #Success! This is our new root
                            DEBUG("Success! Found tag and attribute!")
                            newroot = c
                            break
                    else:
                        newroot = c
                        break
            if newroot: 
                root = newroot
            else:
                # Need to create a node etc.
                newroot = r.createElement(n)
                # add the required attribute if it exists
                if attribute[0]: newroot.attributes[attribute[0]] = attribute[1]
                root.appendChild(newroot)
                root = newroot
    # If we get here, we ned to add a text node
    docnode = root.createElement(v)
    root.appendChild(v)
    return
                
      
def writedeploymentfile (config, filespec, hostcount):
    setmessage("")
    if config == "":
        ERROR("no XML to write as deployment file.")
        return False
    x = updatehostcount(config,hostcount)
    if x is None: return False
    
    try:
        fid = open(filespec ,"w")
        DEBUG("writing deployment file " + filespec)
        result = fid.write(x)
        fid.close()
        return True
    except Exception as e:
        setmessage("INTERNAL ERROR CAN'T WRITE FILE: " + str(e))
        ERROR("INTERNAL ERROR CAN'T WRITE FILE: " + str(e))
        return False

    
def findattribute(xml,attribute, depth):
    if depth > 100:
        print "Exiting at depth " + str(depth)
        exit()
    for child in xml.childNodes:
        #print child.nodeName + "..."
        foundattribute = None 
        atts = child.attributes
        if (atts):
            if attribute in atts.keys():
                #if attribute in atts: 
                #print str(atts.keys())
                foundattribute = atts[attribute].value
                #print str(foundattribute)
        if foundattribute is not None:
           print foundattribute + "," + quote(smash(innertext(child) )) #innertext(child)  or  child.toxml()
        else:
            findattribute(child,attribute,depth+1)

def innertext(node):
        if node.nodeType ==  node.TEXT_NODE:
            return node.data
        else:
            text_string = ""
            for child_node in node.childNodes:
                text_string = text_string + innertext( child_node )
            return text_string

def smash(text):
    # First compress it
    text = ' '.join(text.split())
    return text
    
def quote(text):
    text = text.replace("\\","\\\\")
    text = text.replace("\"","\\\"")
    #Catch quotation marks
    return '"' + text + '"'
